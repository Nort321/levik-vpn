package com.leviknet.vpn.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
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

    suspend fun detect(): WhitelistMode = detectionMutex.withLock {
        val network = directNetwork() ?: return@withLock WhitelistMode.UNKNOWN
        val now = SystemClock.elapsedRealtime()
        if (network == cachedNetwork && now - cachedAtElapsedMs < CACHE_TTL_MS) {
            return@withLock cachedMode
        }

        val mode = withTimeoutOrNull(DETECTION_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                supervisorScope {
                    PROBES.map { probe ->
                        async {
                            WhitelistProbeResult(
                                id = probe.id,
                                domestic = probe.domestic,
                                reachable = probe(network, probe.url),
                            )
                        }
                    }.awaitAll()
                }
            }
        }?.let(::classifyWhitelistMode) ?: WhitelistMode.UNKNOWN

        cachedNetwork = network
        cachedAtElapsedMs = now
        cachedMode = mode
        mode
    }

    // activeNetwork points at the VPN while connected; enumeration is required to find
    // the underlying physical network and remains the compatible API on Android 8-16.
    @Suppress("DEPRECATION")
    private fun directNetwork(): Network? = connectivityManager.allNetworks
        .mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return@mapNotNull null
            }
            network to transportPriority(capabilities)
        }
        .maxByOrNull { (_, priority) -> priority }
        ?.first

    private fun probe(network: Network, target: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (network.openConnection(URL(target)) as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LevikVPN/${com.leviknet.vpn.BuildConfig.VERSION_NAME}")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")
            }
            connection.responseCode in 200..499
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
        private const val PROBE_TIMEOUT_MS = 1_500
        private const val DETECTION_TIMEOUT_MS = 2_500L
        private const val CACHE_TTL_MS = 5 * 60_000L
        private val PROBES = listOf(
            Probe("ozon", "https://www.ozon.ru/", domestic = true),
            Probe("yandex", "https://ya.ru/", domestic = true),
            Probe("avito", "https://www.avito.ru/", domestic = true),
            Probe("google", "https://www.google.com/generate_204", domestic = false),
            Probe("gmail", "https://gmail.com/", domestic = false),
            Probe("aliexpress", "https://aliexpress.ru/", domestic = false),
        )
    }
}
