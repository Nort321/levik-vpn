package com.leviknet.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.leviknet.vpn.core.platform.FavoriteNodes
import com.leviknet.vpn.core.platform.ProtocolAdvice
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Smart Auto on the device, docs/app-platform.md "Smart Auto": ranks servers
 * with [SmartServerRanking], checks that a new connection really carries
 * traffic and remembers per network which protocols do.
 */
class SmartAuto(
    private val context: Context,
    private val history: ProtocolHistoryStore,
    private val nodeLoad: () -> Map<String, Double>,
    private val advice: () -> ProtocolAdvice?,
    private val physicalNetwork: () -> Network?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Checks outlive the caller when it moves on; each one ends by its own deadline. */
    private val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableSwitches = MutableSharedFlow<SmartSwitch>(extraBufferCapacity = 1)

    /** Smart Auto moved the connection to another server; the app follows it. */
    val switches: SharedFlow<SmartSwitch> = mutableSwitches.asSharedFlow()

    fun reportSwitch(switch: SmartSwitch) {
        mutableSwitches.tryEmit(switch)
    }

    /** The server that last carried traffic on this network; reconnecting there skips the search. */
    fun lastWinner(network: Network?): String? = networkKey(network)?.let(history.current()::winner)

    /** Best first. [latencies] come from [ServerPinger]; [network] is the physical network, by default the current one. */
    fun rank(
        servers: List<TunnelServer>,
        latencies: Map<String, Long?>,
        network: Network? = null,
    ): List<RankedServer> {
        val key = networkKey(network ?: physicalNetwork())
        val load = nodeLoad()
        val snapshot = history.current()
        val now = clock()
        val candidates = servers.map { server ->
            val protocol = server.smartProtocol()
            SmartCandidate(
                id = server.id,
                protocol = protocol,
                latencyMs = latencies[server.id],
                silenceMeansDown = ServerPinger.silenceMeansDown(server),
                load = FavoriteNodes.nodeId(server)?.let(load::get),
                verdict = snapshot.verdict(key, protocol, now),
            )
        }
        return SmartServerRanking.rank(candidates, advice())
    }

    fun recordSuccess(server: TunnelServer, network: Network?) {
        val key = networkKey(network) ?: return
        history.update { it.recordSuccess(key, server.smartProtocol(), clock(), serverId = server.id) }
    }

    fun recordFailure(server: TunnelServer, network: Network?) {
        val key = networkKey(network) ?: return
        val node = FavoriteNodes.nodeId(server) ?: server.id
        history.update { it.recordFailure(key, server.smartProtocol(), node, clock(), failedServerId = server.id) }
    }

    /**
     * A real download through the tunnel. Operators that throttle a protocol
     * often let the handshake and small requests through and freeze the
     * connection after about 16 KB, so a ping or a status check says nothing.
     */
    suspend fun checkTraffic(vpnNetwork: Network): Boolean {
        repeat(TRAFFIC_CHECK_ROUNDS) { round ->
            if (round > 0) delay(TRAFFIC_CHECK_RETRY_MS)
            if (anyAnswers(TRAFFIC_CHECK_URLS) { download(vpnNetwork, it) }) return true
        }
        return false
    }

    /** Whether the phone's own network reaches foreign sites; without that a failed check says nothing about the protocol. */
    suspend fun physicalWorks(network: Network): Boolean =
        anyAnswers(PHYSICAL_CHECK_URLS) { reaches(network, it) }

    /**
     * A salted hash naming the network without its name or addresses: the
     * operator code (MCC-MNC) on mobile data, the gateway and DNS servers otherwise.
     */
    fun networkKey(network: Network?): String? {
        val target = network ?: return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val capabilities = manager.getNetworkCapabilities(target) ?: return null
        val raw = if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val operator = runCatching {
                context.getSystemService(TelephonyManager::class.java)?.networkOperator
            }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
            "cell|$operator"
        } else {
            val link = manager.getLinkProperties(target) ?: return null
            val gateways = link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }
            val dns = link.dnsServers.mapNotNull { it.hostAddress }
            if (gateways.isEmpty() && dns.isEmpty()) return null
            "lan|${gateways.sorted().joinToString(",")}|${dns.sorted().joinToString(",")}|${link.domains.orEmpty()}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest("levik-net-v1|$raw".toByteArray(Charsets.UTF_8))
        return digest.take(NETWORK_KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** True as soon as one URL passes; false when all fail. Slower checks finish on their own. */
    private suspend fun anyAnswers(urls: List<String>, check: (String) -> Boolean): Boolean {
        val result = CompletableDeferred<Boolean>()
        val jobs = urls.map { url -> checkScope.launch { if (check(url)) result.complete(true) } }
        checkScope.launch {
            jobs.joinAll()
            result.complete(false)
        }
        return result.await()
    }

    private fun download(network: Network, url: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + TRAFFIC_CHECK_DEADLINE_MS
        var connection: HttpURLConnection? = null
        return try {
            connection = (network.openConnection(URL(url)) as HttpURLConnection).apply {
                connectTimeout = CHECK_TIMEOUT_MS
                readTimeout = CHECK_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Cache-Control", "no-cache")
                // Compressed zeros would cross the tunnel as a few hundred bytes.
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (total < TRAFFIC_CHECK_BYTES) {
                    // A trickle that never reaches the size in time is a throttled tunnel.
                    if (SystemClock.elapsedRealtime() > deadline) return false
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                }
                total >= TRAFFIC_CHECK_BYTES
            }
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun reaches(network: Network, url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (network.openConnection(URL(url)) as HttpURLConnection).apply {
                connectTimeout = CHECK_TIMEOUT_MS
                readTimeout = CHECK_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
            }
            connection.responseCode in 100..599
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TRAFFIC_CHECK_BYTES = 64 * 1024
        const val TRAFFIC_CHECK_ROUNDS = 2
        const val TRAFFIC_CHECK_RETRY_MS = 1_500L
        const val TRAFFIC_CHECK_DEADLINE_MS = 8_000L
        const val CHECK_TIMEOUT_MS = 5_000
        const val NETWORK_KEY_BYTES = 8
        val TRAFFIC_CHECK_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=131072",
            "https://cachefly.cachefly.net/100kb.test",
        )
        // Foreign addresses only: under a mobile whitelist every protocol fails, which says
        // nothing about the protocols; mobile servers handle that case.
        val PHYSICAL_CHECK_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://1.1.1.1/cdn-cgi/trace",
        )
    }
}

/**
 * Smart Auto moved the connection to [toServerId]; protocols by their telemetry
 * names. [protocolFailed] when [fromProtocol] carried no traffic, which the app
 * tells the user; otherwise the network's last winner was simply closer at hand.
 */
data class SmartSwitch(
    val fromProtocol: String,
    val toServerId: String,
    val toProtocol: String,
    val protocolFailed: Boolean,
)

/** Servers and protocols tried since the user connected; a few rungs at most, so a bad network cannot loop. */
internal class SmartLadder(private val maxClimbs: Int = MAX_CLIMBS) {
    private val triedIds = mutableSetOf<String>()
    private val triedProtocols = mutableSetOf<String>()
    private var climbs = 0

    @Synchronized
    fun tried(id: String, protocol: String) {
        triedIds += id
        triedProtocols += protocol
    }

    @Synchronized
    fun climbed() {
        climbs += 1
    }

    @Synchronized
    fun exhausted(): Boolean = climbs >= maxClimbs

    @Synchronized
    fun ids(): Set<String> = triedIds.toSet()

    @Synchronized
    fun protocols(): Set<String> = triedProtocols.toSet()

    @Synchronized
    fun reset() {
        triedIds.clear()
        triedProtocols.clear()
        climbs = 0
    }

    private companion object {
        const val MAX_CLIMBS = 3
    }
}

/** A protocol's name for people, from its telemetry name. */
fun smartProtocolLabel(protocol: String): String = when (protocol) {
    "vless-reality" -> "VLESS Reality"
    "vless-xhttp" -> "VLESS XHTTP"
    "vless-ws" -> "VLESS WS"
    "vless-grpc" -> "VLESS gRPC"
    "vless-tcp" -> "VLESS"
    "hysteria2" -> "Hysteria 2"
    "tuic" -> "TUIC"
    "trojan" -> "Trojan"
    "shadowsocks" -> "Shadowsocks"
    else -> protocol.uppercase()
}

/** The rung of the protocol ladder a server is on. */
internal fun TunnelServer.smartProtocol(): String = telemetryProtocol().wire

/** Regular servers Smart Auto may pick: home exits, relays and mobile servers are separate choices. */
fun TunnelServer.isSmartAutoCandidate(): Boolean = isEligibleForAutomaticSelection() && !isMobileServer()
