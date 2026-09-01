package com.leviknet.vpn.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class WhitelistMode {
    UNKNOWN,
    INACTIVE,
    ACTIVE,
}

internal data class WhitelistProbeResult(
    val id: String,
    val domestic: Boolean,
    val reachable: Boolean,
)

internal fun classifyWhitelistMode(results: List<WhitelistProbeResult>): WhitelistMode {
    val domestic = results.filter(WhitelistProbeResult::domestic)
    val external = results.filterNot(WhitelistProbeResult::domestic)
    if (domestic.size < 3 || external.size < 3) return WhitelistMode.UNKNOWN

    val domesticReachable = domestic.count(WhitelistProbeResult::reachable)
    val externalReachable = external.count(WhitelistProbeResult::reachable)
    return when {
        domesticReachable >= 2 && externalReachable == 0 -> WhitelistMode.ACTIVE
        externalReachable >= 1 -> WhitelistMode.INACTIVE
        else -> WhitelistMode.UNKNOWN
    }
}

/** Detects provider allow-list mode on the physical network without routing probes through VPN. */
class WhitelistDetector(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val detectionMutex = Mutex()
    private var cachedNetwork: Network? = null
    private var cachedAtElapsedMs = 0L
    private var cachedMode = WhitelistMode.UNKNOWN

    suspend fun detect(forceRefresh: Boolean = false): WhitelistMode {
        val network = directNetwork() ?: return WhitelistMode.UNKNOWN
        return detect(network, forceRefresh = forceRefresh)
    }

    suspend fun detect(
        network: Network,
        forceRefresh: Boolean,
    ): WhitelistMode = detectionMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh && network == cachedNetwork && cachedMode != WhitelistMode.UNKNOWN && now - cachedAtElapsedMs < CACHE_TTL_MS) {
            return@withLock cachedMode
        }

        val mode = withTimeoutOrNull(DETECTION_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                supervisorScope {
                    PROBES.map { probe ->
                        async {
                            val reachable = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                                probe(network, probe.url)
                            } ?: false
                            WhitelistProbeResult(
                                id = probe.id,
                                domestic = probe.domestic,
                                reachable = reachable,
                            )
                        }
                    }.awaitAll()
                }
            }
        }?.let(::classifyWhitelistMode) ?: WhitelistMode.UNKNOWN

        cachedNetwork = network
        if (mode != WhitelistMode.UNKNOWN) {
            cachedAtElapsedMs = now
            cachedMode = mode
        } else {
            cachedAtElapsedMs = 0L
            cachedMode = WhitelistMode.UNKNOWN
        }
        mode
    }

    // activeNetwork points at the VPN while connected; enumeration is required to find
    // the underlying physical network and remains the compatible API on Android 8-16.
    @Suppress("DEPRECATION")
    private fun directNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        if (active != null) {
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return active
            }
        }
        return connectivityManager.allNetworks
            .mapNotNull { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    return@mapNotNull null
                }
                network to transportPriority(capabilities)
            }
            .maxByOrNull { (_, priority) -> priority }
            ?.first
    }

    private suspend fun probe(network: Network, target: String): Boolean =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                suspendCancellableCoroutine { continuation ->
                    try {
                        val conn = (network.openConnection(URL(target)) as HttpURLConnection).apply {
                            connectTimeout = PROBE_TIMEOUT_MS.toInt()
                            readTimeout = PROBE_TIMEOUT_MS.toInt()
                            instanceFollowRedirects = false
                            requestMethod = "GET"
                            setRequestProperty(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                            )
                            setRequestProperty("Cache-Control", "no-cache")
                            setRequestProperty("Connection", "close")
                        }
                        connection = conn
                        continuation.invokeOnCancellation {
                            runCatching { conn.disconnect() }
                        }
                        val responseCode = conn.responseCode
                        continuation.resume(responseCode in 200..599)
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

    private fun transportPriority(capabilities: NetworkCapabilities): Int = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
        else -> 0
    } + if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0

    private data class Probe(
        val id: String,
        val url: String,
        val domestic: Boolean,
    )

    companion object {
        private const val PROBE_TIMEOUT_MS = 2_500L
        private const val DETECTION_TIMEOUT_MS = 5_000L
        private const val CACHE_TTL_MS = 60_000L
        private val PROBES = listOf(
            Probe("ozon", "https://www.ozon.ru/", domestic = true),
            Probe("yandex", "https://ya.ru/", domestic = true),
            Probe("avito", "https://www.avito.ru/", domestic = true),
            Probe("vk", "https://vk.com/", domestic = true),
            Probe("google", "https://www.google.com/generate_204", domestic = false),
            Probe("cloudflare", "https://cp.cloudflare.com/", domestic = false),
            Probe("wikipedia", "https://www.wikipedia.org/", domestic = false),
            Probe("apple", "https://www.apple.com/library/test/success.html", domestic = false),
        )
    }
}

