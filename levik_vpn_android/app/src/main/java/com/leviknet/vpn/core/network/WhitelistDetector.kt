package com.leviknet.vpn.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.leviknet.vpn.core.logger.AppLogger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class WhitelistMode {
    UNKNOWN,
    INACTIVE,
    ACTIVE,
}

enum class WhitelistProbeOutcome(val wire: String) {
    OK("ok"),
    DNS("dns"),
    CONNECT("connect"),
    READ("read"),
    TLS("tls"),
    HTTP("http"),
    SHORT_BODY("short_body"),
    DEADLINE("deadline"),
    OTHER("other"),
}

/** Fixed probe IDs and numeric measurements only; never contains addresses or exception messages. */
data class WhitelistProbeDiagnostic(
    val id: String,
    val domestic: Boolean,
    val outcome: WhitelistProbeOutcome,
    val elapsedMs: Long,
    val receivedBytes: Int,
    val httpStatus: Int?,
    /** Measured for the server's learning only; never part of the decision. */
    val candidate: Boolean = false,
) {
    val reachable: Boolean get() = outcome == WhitelistProbeOutcome.OK
}

data class WhitelistDetectionDiagnostic(
    val mode: WhitelistMode,
    val elapsedMs: Long,
    val probes: List<WhitelistProbeDiagnostic>,
)

/** A forced measurement for the anonymous map: the mode and every probe's outcome. */
data class WhitelistObservation(
    val mode: WhitelistMode,
    /** Probe id → [WhitelistProbeOutcome.wire]; decision probes and candidates. */
    val outcomes: Map<String, String>,
)

internal data class WhitelistProbeResult(
    val id: String,
    val domestic: Boolean,
    val reachable: Boolean,
)

internal fun whitelistProbeFailureOutcome(error: Exception, readingBody: Boolean): WhitelistProbeOutcome {
    if (error is CancellationException) throw error
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is UnknownHostException } -> WhitelistProbeOutcome.DNS
        causes.any { it is SSLException } -> WhitelistProbeOutcome.TLS
        causes.any { it is SocketTimeoutException || it is SocketException } ->
            if (readingBody) WhitelistProbeOutcome.READ else WhitelistProbeOutcome.CONNECT
        readingBody && causes.any { it is IOException } -> WhitelistProbeOutcome.READ
        else -> WhitelistProbeOutcome.OTHER
    }
}

internal fun whitelistProbeResponseOutcome(
    httpStatus: Int,
    receivedBytes: Int,
    requiredBytes: Int,
): WhitelistProbeOutcome = when {
    requiredBytes == 0 -> if (httpStatus in 200..599) WhitelistProbeOutcome.OK else WhitelistProbeOutcome.HTTP
    httpStatus != HttpURLConnection.HTTP_OK -> WhitelistProbeOutcome.HTTP
    receivedBytes < requiredBytes -> WhitelistProbeOutcome.SHORT_BODY
    else -> WhitelistProbeOutcome.OK
}

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

/**
 * Detects provider allow-list mode on the physical network without routing probes through VPN.
 * The probe set comes from the signed catalog, chosen by the network's region and operator.
 */
class WhitelistDetector(
    context: Context,
    private val catalog: () -> AllowlistProbeCatalog = { AllowlistProbeCatalog.BUILT_IN },
    private val scopeOf: (Network) -> AllowlistProbeScope? = { null },
    private val onDiagnostic: (WhitelistDetectionDiagnostic) -> Unit = {},
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val detectionMutex = Mutex()
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cachedNetwork: Network? = null
    private var cachedAtElapsedMs = 0L
    private var cachedMode = WhitelistMode.UNKNOWN
    private var cachedSet: String? = null

    suspend fun detect(forceRefresh: Boolean = false): WhitelistMode {
        val network = directNetwork() ?: return WhitelistMode.UNKNOWN
        return detect(network, forceRefresh = forceRefresh)
    }

    suspend fun detect(
        network: Network,
        forceRefresh: Boolean,
    ): WhitelistMode = measure(network, forceRefresh, scopeOf(network), candidates = 0).mode

    /**
     * A fresh measurement with [scope] (from the map's region token) plus a few candidate
     * sites for the server's learning. The candidates never change the answer.
     */
    suspend fun observe(network: Network, scope: AllowlistProbeScope?): WhitelistObservation {
        val diagnostic = measure(network, forceRefresh = true, scope = scope, candidates = MAX_REPORTED_PROBES)
        return WhitelistObservation(
            mode = diagnostic.mode,
            outcomes = diagnostic.probes.take(MAX_REPORTED_PROBES).associate { it.id to it.outcome.wire },
        )
    }

    private suspend fun measure(
        network: Network,
        forceRefresh: Boolean,
        scope: AllowlistProbeScope?,
        candidates: Int,
    ): WhitelistDetectionDiagnostic = detectionMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val probeCatalog = catalog()
        val set = "${probeCatalog.sequence}:${probeCatalog.setNameFor(scope)}"
        if (!forceRefresh && network == cachedNetwork && set == cachedSet && cachedMode != WhitelistMode.UNKNOWN &&
            now - cachedAtElapsedMs < CACHE_TTL_MS
        ) {
            return@withLock WhitelistDetectionDiagnostic(cachedMode, 0L, emptyList())
        }
        val decisive = probeCatalog.probesFor(scope)
        val extra = probeCatalog.candidatesFor(scope, minOf(MAX_CANDIDATES, candidates - decisive.size))

        // Every probe has its own deadline, so a slow one never discards the others' answers.
        val probes = coroutineScope {
            decisive.map { probe -> async { reachable(network, probe, candidate = false) } } +
                extra.map { probe -> async { reachable(network, probe, candidate = true) } }
        }.awaitAll()
        val mode = classifyWhitelistMode(
            probes.filterNot(WhitelistProbeDiagnostic::candidate).map { WhitelistProbeResult(it.id, it.domestic, it.reachable) },
        )
        currentCoroutineContext().ensureActive()

        cachedSet = set
        cachedNetwork = network
        if (mode != WhitelistMode.UNKNOWN) {
            cachedAtElapsedMs = now
            cachedMode = mode
        } else {
            cachedAtElapsedMs = 0L
            cachedMode = WhitelistMode.UNKNOWN
        }
        val diagnostic = WhitelistDetectionDiagnostic(
            mode = mode,
            elapsedMs = (SystemClock.elapsedRealtime() - now).coerceAtLeast(0L),
            probes = probes,
        )
        AppLogger.i(
            "WhitelistDetector",
            "Allowlists: mode=${mode.name} ms=${diagnostic.elapsedMs} set=$set; " + probes.joinToString("; ") {
                (if (it.candidate) "+" else "") +
                    "${it.id}=${it.outcome.wire},ms=${it.elapsedMs},bytes=${it.receivedBytes},http=${it.httpStatus ?: 0}"
            },
        )
        try {
            onDiagnostic(diagnostic)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A diagnostic consumer must not change a completed network decision.
            AppLogger.w("WhitelistDetector", "Allowlists: diagnostic consumer unavailable")
        }
        diagnostic
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
    private suspend fun reachable(network: Network, probe: AllowlistProbe, candidate: Boolean): WhitelistProbeDiagnostic {
        val progress = ProbeProgress(candidate)
        val attempt = probeScope.async { probe(network, probe, progress) }
        return try {
            withTimeoutOrNull(PROBE_DEADLINE_MS) { attempt.await() }
                ?: progress.result(probe, WhitelistProbeOutcome.DEADLINE)
        } finally {
            if (!attempt.isCompleted) {
                attempt.cancel()
                progress.connection.get()?.let { runCatching { it.disconnect() } }
            }
        }
    }

    private fun probe(
        network: Network,
        probe: AllowlistProbe,
        progress: ProbeProgress,
    ): WhitelistProbeDiagnostic {
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
            progress.connection.set(conn)
            val code = conn.responseCode
            progress.httpStatus.set(code)
            if (probe.minBytes == 0) {
                return progress.result(probe, whitelistProbeResponseOutcome(code, 0, 0))
            }
            if (code != HttpURLConnection.HTTP_OK) return progress.result(probe, WhitelistProbeOutcome.HTTP)
            conn.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < probe.minBytes) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    progress.receivedBytes.set(total)
                }
                progress.result(probe, whitelistProbeResponseOutcome(code, total, probe.minBytes))
            }
        } catch (error: Exception) {
            progress.result(probe, whitelistProbeFailureOutcome(error, readingBody = progress.httpStatus.get() >= 0))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private class ProbeProgress(private val candidate: Boolean) {
        private val startedAtElapsedMs = SystemClock.elapsedRealtime()
        val connection = AtomicReference<HttpURLConnection?>()
        val receivedBytes = AtomicInteger(0)
        val httpStatus = AtomicInteger(-1)

        fun result(probe: AllowlistProbe, outcome: WhitelistProbeOutcome) = WhitelistProbeDiagnostic(
            id = probe.id,
            domestic = probe.domestic,
            candidate = candidate,
            outcome = outcome,
            elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L),
            receivedBytes = receivedBytes.get(),
            httpStatus = httpStatus.get().takeIf { it >= 0 },
        )
    }

    private fun transportPriority(capabilities: NetworkCapabilities): Int = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
        else -> 0
    } + if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0

    // A foreign site counts only after AllowlistProbe.minBytes of its body arrive. Some
    // operators (Beeline) let a TLS handshake and a tiny answer through under allowlists
    // and stall the transfer after a few kilobytes, so a status code alone means nothing.

    companion object {
        // A congested mobile network can take a few seconds for one TLS handshake and 32 KB.
        private const val SOCKET_TIMEOUT_MS = 4_000
        private const val PROBE_DEADLINE_MS = 6_000L
        private const val CACHE_TTL_MS = 60_000L
        /** A map report carries at most this many probe outcomes (server limit 16). */
        internal const val MAX_REPORTED_PROBES = 16
        private const val MAX_CANDIDATES = 4
    }
}
