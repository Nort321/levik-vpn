package com.leviknet.vpn.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Allowlists: domestic sites answer, foreign ones do not. One foreign site may
 * still answer under allowlists: CDNs (Akamai, Google) keep caches inside the
 * operator's own network, and the operator lets its own addresses through.
 * Two foreign answers mean the internet is open.
 */
internal fun classifyWhitelistMode(results: List<WhitelistProbeResult>): WhitelistMode {
    val domestic = results.filter(WhitelistProbeResult::domestic)
    val external = results.filterNot(WhitelistProbeResult::domestic)
    if (domestic.size < 3 || external.size < 4) return WhitelistMode.UNKNOWN

    val domesticReachable = domestic.count(WhitelistProbeResult::reachable)
    val externalReachable = external.count(WhitelistProbeResult::reachable)
    return when {
        externalReachable >= 2 -> WhitelistMode.INACTIVE
        domesticReachable >= 2 -> WhitelistMode.ACTIVE
        else -> WhitelistMode.UNKNOWN
    }
}

/** Detects provider allow-list mode on the physical network without routing probes through VPN. */
class WhitelistDetector(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val detectionMutex = Mutex()
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

        // Every probe has its own deadline, so a slow one never discards the others' answers.
        val mode = coroutineScope {
            PROBES.map { probe ->
                async {
                    WhitelistProbeResult(
                        id = probe.id,
                        domestic = probe.domestic,
                        reachable = reachable(network, probe.url),
                    )
                }
            }.awaitAll()
        }.let(::classifyWhitelistMode)

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
    internal fun directNetwork(): Network? {
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

    internal fun isCellular(network: Network): Boolean =
        connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    /**
     * Under allowlists a foreign address can hang in DNS or in connect past the socket
     * timeouts, and a blocking call ignores coroutine cancellation. The probe runs on its
     * own and is abandoned at the deadline instead of holding up the answer.
     */
    private suspend fun reachable(network: Network, target: String): Boolean {
        val connection = AtomicReference<HttpURLConnection?>()
        val attempt = probeScope.async { probe(network, target, connection) }
        return try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { attempt.await() } ?: false
        } finally {
            if (!attempt.isCompleted) {
                attempt.cancel()
                connection.get()?.let { runCatching { it.disconnect() } }
            }
        }
    }

    private fun probe(
        network: Network,
        target: String,
        connection: AtomicReference<HttpURLConnection?>,
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (network.openConnection(URL(target)) as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS.toInt()
                readTimeout = PROBE_TIMEOUT_MS.toInt()
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")
            }
            connection.set(conn)
            conn.responseCode in 200..599
        } catch (_: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
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
        // A congested mobile network can take a few seconds for one TLS handshake.
        private const val PROBE_TIMEOUT_MS = 4_000L
        private const val CACHE_TTL_MS = 60_000L
        private val PROBES = listOf(
            Probe("ozon", "https://www.ozon.ru/", domestic = true),
            Probe("yandex", "https://ya.ru/", domestic = true),
            Probe("avito", "https://www.avito.ru/", domestic = true),
            Probe("vk", "https://vk.com/", domestic = true),
            Probe("google", "https://www.google.com/generate_204", domestic = false),
            Probe("cloudflare", "https://cp.cloudflare.com/", domestic = false),
            Probe("wikipedia", "https://www.wikipedia.org/", domestic = false),
            Probe("github", "https://github.com/", domestic = false),
            Probe("apple", "https://www.apple.com/library/test/success.html", domestic = false),
        )
    }
}
