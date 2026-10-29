package com.leviknet.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.leviknet.vpn.core.platform.FavoriteNodes
import com.leviknet.vpn.core.platform.NodeLoadView
import com.leviknet.vpn.core.platform.ProtocolAdvice
import com.leviknet.vpn.core.platform.SmartRouteResource
import com.leviknet.vpn.data.hasUsableYandexAuth
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Smart Auto on the device, docs/app-platform.md "Smart Auto": ranks servers
 * with [SmartServerRanking], checks that a new connection really carries
 * traffic and remembers per network which protocols do.
 */
class SmartAuto(
    private val context: Context,
    private val history: ProtocolHistoryStore,
    private val loadView: () -> NodeLoadView,
    /** A random salt that never leaves the phone, for [SmartServerRanking.spreadSeed]. */
    private val spreadSalt: () -> String,
    private val advice: () -> ProtocolAdvice?,
    private val physicalNetwork: () -> Network?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val probeSettings: () -> List<String> = { emptyList() },
) {
    private val liveOwner = AtomicLong(0L)
    val liveMainManaged: Boolean get() = liveOwner.get() != 0L
    fun liveSessionStarted(owner: Long) { liveOwner.set(owner) }
    fun liveSessionStopped(owner: Long) { liveOwner.compareAndSet(owner, 0L) }
    private data class MeasuredRoute(val network: String, val routeId: String?, val transport: String, val atMs: Long, val value: SmartRouteMeasurement)
    private val measuredRoutes = ConcurrentHashMap<String, MeasuredRoute>()

    private fun measurementNetwork(network: Network?): String? = network?.let { "${it.networkHandle}|${networkKey(it).orEmpty()}" }

    fun routeResources(servers: List<TunnelServer>): Map<String, SmartRouteResource> {
        val routes = loadView().routes
        return servers.mapNotNull { server ->
            val host = server.syncHost() ?: return@mapNotNull null
            val route = server.syncPort()?.let { FavoriteNodes.endpointId(host, it) }?.let(routes::get)
                ?: FavoriteNodes.nodeId(host)?.let(routes::get)
            route?.let { server.id to it }
        }.toMap()
    }

    fun measurementContext(server: TunnelServer): String = SmartRouteCacheContext.fingerprint(server, probeSettings())

    fun eligibleRoutes(servers: List<TunnelServer>): List<TunnelServer> {
        val view = loadView()
        val full = servers.filter { FavoriteNodes.load(view.full.associateWith { 1.0 }, it) != null }.mapTo(mutableSetOf(), TunnelServer::id)
        val eligible = SmartRouteSelection.eligibleIds(servers.map(TunnelServer::id), routeResources(servers), full)
        return servers.filter { it.id in eligible }
    }

    fun recordRouteMeasurement(server: TunnelServer, network: Network, measurement: SmartRouteMeasurement, transport: String = measurementContext(server), routeId: String? = routeResources(listOf(server))[server.id]?.id) {
        val key = measurementNetwork(network) ?: return
        val now = clock()
        measuredRoutes.entries.removeIf { now - it.value.atMs >= it.value.value.cacheMs }
        if (measuredRoutes.size >= 256) measuredRoutes.clear()
        measuredRoutes[server.id] = MeasuredRoute(key, routeId, transport, now, measurement)
    }

    fun routeMeasurements(servers: List<TunnelServer>, network: Network? = null): Map<String, SmartRouteMeasurement> {
        val key = measurementNetwork(network ?: physicalNetwork()) ?: return emptyMap()
        val resources = routeResources(servers)
        val now = clock()
        return servers.mapNotNull { server -> measuredRoutes[server.id]?.takeIf {
            it.network == key && now - it.atMs in 0 until it.value.cacheMs && it.routeId == resources[server.id]?.id && it.transport == measurementContext(server)
        }?.let { server.id to it.value } }.toMap()
    }

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

    /**
     * Mobile servers under allowlists, best first: the relays the user is
     * signed in to, the ordinary ones, then the other relays, see
     * [SmartServerRanking.rankAllowlist]. An operator lets through some
     * addresses and not others, so the record is about servers, not
     * protocols, and kept apart from the same network without allowlists.
     */
    fun rankAllowlist(
        servers: List<TunnelServer>,
        latencies: Map<String, Long?>,
        network: Network?,
    ): List<RankedServer> {
        val key = networkKey(network, allowlist = true)
        val view = loadView()
        val snapshot = history.current()
        val now = clock()
        val vkSession = storedVkSession()
        val winner = key?.let(snapshot::winner)
        val candidates = servers.map { server ->
            val record = allowlistRecord(server)
            AllowlistCandidate(
                candidate = SmartCandidate(
                    id = server.id,
                    protocol = server.smartProtocol(),
                    latencyMs = latencies[server.id],
                    silenceMeansDown = ServerPinger.silenceMeansDown(server),
                    load = SmartServerRanking.agedLoad(FavoriteNodes.load(view.load, server), view.ageMs),
                    verdict = snapshot.verdict(key, record, now),
                ),
                relay = server.isAllowlistMobileServer(),
                failedRecently = key != null && snapshot.failedWithin(key, record, now, ALLOWLIST_RECENT_FAILURE_MS),
                signedIn = server.isSignedInRelay(vkSession, Instant.ofEpochMilli(now)),
                lastWinner = server.id == winner,
            )
        }
        return SmartServerRanking.rankAllowlist(candidates)
    }

    fun recordAllowlistSuccess(server: TunnelServer, network: Network?) {
        val key = networkKey(network, allowlist = true) ?: return
        history.update { it.recordSuccess(key, allowlistRecord(server), clock(), serverId = server.id) }
    }

    fun recordAllowlistFailure(server: TunnelServer, network: Network?) {
        val key = networkKey(network, allowlist = true) ?: return
        history.update { it.recordFailure(key, allowlistRecord(server), server.id, clock(), failedServerId = server.id) }
    }

    /**
     * Best first. [latencies] come from [ServerPinger]; [network] is the physical network, by default the current one.
     * [relays] are the relay entries among all the profile's servers, see [relayHosts]; a subset such as one
     * country cannot tell them.
     */
    fun rank(
        servers: List<TunnelServer>,
        latencies: Map<String, Long?>,
        network: Network? = null,
        relays: Set<String> = relayHosts(servers),
    ): List<RankedServer> {
        val key = networkKey(network ?: physicalNetwork())
        val view = loadView()
        val snapshot = history.current()
        val now = clock()
        val eligible = eligibleRoutes(servers)
        val candidates = eligible.map { server -> candidate(server, latencies, view, snapshot, key, now, relays) }
        val fallback = SmartServerRanking.rank(candidates, advice(), SmartServerRanking.spreadSeed(spreadSalt(), now))
        val measurements = routeMeasurements(eligible, network)
        val measured = SmartRouteSelection.order(measurements, routeResources(eligible), eligible.map(TunnelServer::id))
            .map { id -> RankedServer(id, servers.first { it.id == id }.smartProtocol(), measurements.getValue(id).delayCost) }
        return measured + fallback.filterNot { item -> measured.any { it.id == item.id } }
    }

    /** [server]'s score as [rank] computes it, without the spread. */
    fun score(server: TunnelServer, latencies: Map<String, Long?>, relays: Set<String>, network: Network? = null): Double {
        routeMeasurements(listOf(server), network)[server.id]?.delayCost?.takeIf(Double::isFinite)?.let { return it }
        val now = clock()
        val candidate = candidate(server, latencies, loadView(), history.current(), networkKey(network ?: physicalNetwork()), now, relays)
        return SmartServerRanking.score(candidate, advice())
    }

    private fun candidate(
        server: TunnelServer,
        latencies: Map<String, Long?>,
        view: NodeLoadView,
        snapshot: ProtocolHistory,
        key: String?,
        now: Long,
        relays: Set<String>,
    ): SmartCandidate {
        val protocol = server.smartProtocol()
        return SmartCandidate(
            id = server.id,
            protocol = protocol,
            latencyMs = SmartServerRanking.rankingLatency(latencies[server.id], server.endpointHost() in relays),
            silenceMeansDown = ServerPinger.silenceMeansDown(server),
            load = SmartServerRanking.agedLoad(FavoriteNodes.load(view.load, server), view.ageMs),
            verdict = snapshot.verdict(key, protocol, now),
            quality = FavoriteNodes.nodeId(server)?.let(view.quality::get),
            full = FavoriteNodes.load(view.full.associateWith { 1.0 }, server) != null || routeResources(listOf(server))[server.id]?.overloaded == true,
        )
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
     * The time of the download that passed, in milliseconds; null when none did.
     */
    suspend fun checkTraffic(vpnNetwork: Network, size: TrafficCheckSize = TrafficCheckSize.REGULAR): Long? {
        repeat(TRAFFIC_CHECK_ROUNDS) { round ->
            if (round > 0) delay(TRAFFIC_CHECK_RETRY_MS)
            firstAnswer(TRAFFIC_CHECK_URLS) { download(vpnNetwork, it, size) }?.let { return it }
        }
        return null
    }

    /** Whether the phone's own network reaches foreign sites; without that a failed check says nothing about the protocol. */
    suspend fun physicalWorks(network: Network): Boolean =
        firstAnswer(PHYSICAL_CHECK_URLS) { url -> 0L.takeIf { reaches(network, url) } } != null

    /** Mobile recovery checks domestic sites: foreign sites may be blocked by the allowlist itself. */
    suspend fun mobilePhysicalWorks(network: Network): Boolean = withTimeoutOrNull(CHECK_TIMEOUT_MS.toLong()) {
        firstAnswer(ALLOWLIST_PHYSICAL_CHECK_URLS) { url -> 0L.takeIf { reaches(network, url) } }
    } != null

    /**
     * A salted hash naming the network without its name or addresses: the
     * operator code (MCC-MNC) on mobile data, the gateway and DNS servers otherwise.
     */
    fun networkKey(network: Network?, allowlist: Boolean = false): String? {
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
        val scope = if (allowlist) "allowlist|" else ""
        val digest = MessageDigest.getInstance("SHA-256").digest("levik-net-v1|$scope$raw".toByteArray(Charsets.UTF_8))
        return digest.take(NETWORK_KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** The first URL's answer as soon as one passes; null when all fail. Slower checks finish on their own. */
    private suspend fun firstAnswer(urls: List<String>, check: (String) -> Long?): Long? {
        val result = CompletableDeferred<Long?>()
        val jobs = urls.map { url -> checkScope.launch { check(url)?.let(result::complete) } }
        checkScope.launch {
            jobs.joinAll()
            result.complete(null)
        }
        return result.await()
    }

    /** Milliseconds the download took; null when it failed or trickled past the deadline. */
    private fun download(network: Network, url: String, size: TrafficCheckSize): Long? {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + size.deadlineMs
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
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (total < size.bytes) {
                    // A trickle that never reaches the size in time is a throttled tunnel.
                    if (SystemClock.elapsedRealtime() > deadline) return null
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                }
                (SystemClock.elapsedRealtime() - startedAt).takeIf { total >= size.bytes }
            }
        } catch (_: Exception) {
            null
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
        const val TRAFFIC_CHECK_ROUNDS = 2
        const val TRAFFIC_CHECK_RETRY_MS = 1_500L
        /** An ordinary mobile server that failed under allowlists this recently waits behind the relays. */
        const val ALLOWLIST_RECENT_FAILURE_MS = 60 * 60_000L
        const val CHECK_TIMEOUT_MS = 5_000
        const val NETWORK_KEY_BYTES = 8
        val TRAFFIC_CHECK_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=131072",
            "https://proof.ovh.net/files/1Mb.dat",
        )
        // Foreign addresses only: under a mobile whitelist every protocol fails, which says
        // nothing about the protocols; mobile servers handle that case.
        val PHYSICAL_CHECK_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://1.1.1.1/cdn-cgi/trace",
        )
        val ALLOWLIST_PHYSICAL_CHECK_URLS = listOf(
            "https://www.ozon.ru/",
            "https://ya.ru/",
            "https://www.avito.ru/",
            "https://vk.com/",
        )
    }
}

/** Smart Auto moved the connection from [fromServerId] to [toServerId]; protocols by their telemetry names. */
data class SmartSwitch(
    val fromServerId: String,
    val fromProtocol: String,
    val toServerId: String,
    val toProtocol: String,
    val reason: SmartSwitchReason,
)

enum class SmartSwitchReason(
    /** Whether the app tells the user; otherwise it only follows the new server. */
    val noticed: Boolean,
) {
    /** The protocol carried no traffic on this network. */
    PROTOCOL_FAILED(noticed = true),

    /** Another server of the same protocol is tried. */
    SERVER_FAILED(noticed = false),

    /** The network's last winner was closer at hand. */
    NETWORK_WINNER(noticed = false),

    /** New core flows use a confirmed better path; established flows keep draining. */
    PATH_QUALITY(noticed = false),

    /** The operator turned allowlists on: a mobile server carries traffic then. */
    ALLOWLIST_ON(noticed = true),

    /** A mobile server carried nothing under allowlists; the next one is tried. */
    ALLOWLIST_NEXT(noticed = true),

    /** Allowlists are off: back to the server used before them. */
    ALLOWLIST_OFF(noticed = true),
}

/** How much a traffic check downloads and how long it may take. */
enum class TrafficCheckSize(val bytes: Int, val deadlineMs: Long) {
    REGULAR(bytes = 64 * 1024, deadlineMs = 8_000L),

    /** Relays under allowlists are slow by design; they only need to carry traffic at all. */
    ALLOWLIST(bytes = 32 * 1024, deadlineMs = 12_000L),
}

/** The relay entries among [servers]: hosts that servers of different countries share. */
fun relayHosts(servers: List<TunnelServer>): Set<String> =
    SmartServerRanking.relayHosts(servers.map { it.endpointHost() to it.countryCode })

private fun TunnelServer.endpointHost(): String? = ServerPinger.extractEndpoint(outbound)?.first?.lowercase(Locale.ROOT)

/** Under allowlists the record is per server: the operator lets some addresses through. */
private fun allowlistRecord(server: TunnelServer): String = "server:${server.id}"

/**
 * A relay the user is already signed in to: WDTT with a stored VK login,
 * Yandex with a usable sign-in. Under allowlists it goes before the ordinary
 * mobile servers; without a sign-in it waits behind them, since the sign-in
 * page scares many users.
 */
internal fun TunnelServer.isSignedInRelay(vkSession: Boolean, now: Instant = Instant.now()): Boolean =
    isAllowlistMobileServer() && when (engine) {
        TunnelEngineKind.LEVIK_RELAY -> vkSession
        TunnelEngineKind.LEVIK_YANDEX -> hasUsableYandexAuth(now)
        else -> false
    }

/** The VK login stored in WebView; without WebView there is none to use. */
internal fun storedVkSession(): Boolean = runCatching(::hasStoredVkSession).getOrDefault(false)

/**
 * Servers and protocols tried since the user connected; a few rungs at most, so a bad network cannot loop.
 * A spent ladder is climbed again after [retryAfterMs]: what failed then may carry traffic now.
 */
internal class SmartLadder(
    private val maxClimbs: Int = MAX_CLIMBS,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val triedIds = mutableSetOf<String>()
    private val triedProtocols = mutableSetOf<String>()
    private var climbs = 0
    private var lastClimbAtMs = 0L

    @Synchronized
    fun tried(id: String, protocol: String) {
        triedIds += id
        triedProtocols += protocol
    }

    @Synchronized
    fun climbed() {
        climbs += 1
        lastClimbAtMs = clock()
    }

    @Synchronized
    fun exhausted(): Boolean {
        if (climbs < maxClimbs) return false
        if (clock() - lastClimbAtMs < retryAfterMs) return true
        // The network's history still puts what failed last time at the back.
        reset()
        return false
    }

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
        const val RETRY_AFTER_MS = 30 * 60_000L
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
