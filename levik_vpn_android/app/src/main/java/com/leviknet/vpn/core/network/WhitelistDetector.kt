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
 * Allowlists: domestic sites answer, foreign ones do not deliver a real download.
 * One foreign site may still deliver under allowlists (a CDN cache inside the
 * operator's network, which the operator lets through). The internet is open
 * only when most foreign sites deliver: leaving a mobile server on a wrong
 * "open" costs the connection, so two out of five stay unknown.
 */
internal fun classifyWhitelistMode(results: List<WhitelistProbeResult>): WhitelistMode {
    val domestic = results.filter(WhitelistProbeResult::domestic)
    val external = results.filterNot(WhitelistProbeResult::domestic)
    if (domestic.size < 3 || external.size < 4) return WhitelistMode.UNKNOWN

    val domesticReachable = domestic.count(WhitelistProbeResult::reachable)
    val externalReachable = external.count(WhitelistProbeResult::reachable)
    return when {
        externalReachable * 2 > external.size -> WhitelistMode.INACTIVE
        domesticReachable >= 2 && externalReachable <= 1 -> WhitelistMode.ACTIVE
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
                        reachable = reachable(network, probe),
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
    private suspend fun reachable(network: Network, probe: Probe): Boolean {
        val connection = AtomicReference<HttpURLConnection?>()
        val attempt = probeScope.async { probe(network, probe, connection) }
        return try {
            withTimeoutOrNull(PROBE_DEADLINE_MS) { attempt.await() } ?: false
        } finally {
            if (!attempt.isCompleted) {
                attempt.cancel()
                connection.get()?.let { runCatching { it.disconnect() } }
            }
        }
    }

    private fun probe(
        network: Network,
        probe: Probe,
        connection: AtomicReference<HttpURLConnection?>,
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (network.openConnection(URL(probe.url)) as HttpURLConnection).apply {
                connectTimeout = SOCKET_TIMEOUT_MS
                readTimeout = SOCKET_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")
                // Compression would let a large page cross a byte cutoff as a few kilobytes.
                if (probe.minBytes > 0) setRequestProperty("Accept-Encoding", "identity")
            }
            connection.set(conn)
            val code = conn.responseCode
            if (probe.minBytes == 0) return code in 200..599
            code == HttpURLConnection.HTTP_OK && conn.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < probe.minBytes) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                }
                total >= probe.minBytes
            }
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

    /**
     * A foreign site counts only after [minBytes] of its body arrive. Some operators
     * (Beeline) let a TLS handshake and a tiny answer through under allowlists and
     * stall the transfer after a few kilobytes, so a status code alone means nothing.
     */
    private data class Probe(
        val id: String,
        val url: String,
        val domestic: Boolean,
        val minBytes: Int = 0,
    )

    companion object {
        // A congested mobile network can take a few seconds for one TLS handshake and 32 KB.
        private const val SOCKET_TIMEOUT_MS = 4_000
        private const val PROBE_DEADLINE_MS = 6_000L
        private const val CACHE_TTL_MS = 60_000L
        private const val FOREIGN_MIN_BYTES = 32 * 1024

        // Foreign hosts with their own networks: no caches inside Russian operators.
        private val PROBES = listOf(
            Probe("ozon", "https://www.ozon.ru/", domestic = true),
            Probe("yandex", "https://ya.ru/", domestic = true),
            Probe("avito", "https://www.avito.ru/", domestic = true),
            Probe("vk", "https://vk.com/", domestic = true),
            Probe("cloudflare", "https://speed.cloudflare.com/__down?bytes=$FOREIGN_MIN_BYTES", domestic = false, FOREIGN_MIN_BYTES),
            Probe("mozilla", "https://www.mozilla.org/en-US/", domestic = false, FOREIGN_MIN_BYTES),
            Probe("ovh", "https://proof.ovh.net/files/1Mb.dat", domestic = false, FOREIGN_MIN_BYTES),
            Probe("github", "https://github.com/", domestic = false, FOREIGN_MIN_BYTES),
            Probe("wikipedia", "https://www.wikipedia.org/", domestic = false, FOREIGN_MIN_BYTES),
        )
    }
}
