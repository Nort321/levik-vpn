package com.leviknet.vpn.vpn

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The profile's Levik Game section (levik_vpn_air game-profile.ts publicGameProfile). */
@Serializable
data class TunnelGameAccess(
    val enabled: Boolean = false,
    val endpoints: List<GameEndpoint> = emptyList(),
    val nodes: List<GameNode>? = null,
    /** Levik Game alone: the subscription has no ordinary servers. */
    val only: Boolean = false,
    /** Signed per-user token for the exit relays; they count this user's limits by it. */
    val relayToken: String? = null,
)

@Serializable
data class GameEndpoint(
    val address: String,
    val port: Int,
    val protocol: String,
    val nodeId: String? = null,
)

@Serializable
data class GameNode(
    val nodeId: String,
    val name: String,
    val countryCode: String = "",
    val city: String? = null,
    /** enabled, draining (no new matches) or disabled (never used). */
    val state: String = "enabled",
)

/** One game node link: Xray reaches its relay through this outbound only. */
@Serializable
data class GameRoute(
    val index: Int,
    val nodeId: String,
    val name: String,
    val countryCode: String,
    val outbound: JsonObject,
)

@Serializable
data class GameServerConfig(
    val routes: List<GameRoute>,
    val nodes: List<GameNode>,
    val relayToken: String? = null,
    val only: Boolean = false,
)

object GameProfile {
    const val SERVER_ID = "levik-game"
    const val SERVER_NAME = "Levik Game"
    const val MAX_ROUTES = 32
    private const val MAX_NODES = 64
    private val NODE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val TOKEN = Regex("TEdUMQ[A-Za-z0-9_-]{120}")
    private val STATES = setOf("enabled", "draining", "disabled")
    private val COUNTRY = Regex("(?:[A-Z]{2})?")

    data class Split(val ordinary: List<TunnelServer>, val game: TunnelServer?)

    /**
     * Separates the game node links from the ordinary servers. A game link never becomes an
     * ordinary server: the game nodes admit only relay traffic. With [includeGame] false (Play)
     * the links are dropped and no Game server is made.
     */
    fun split(servers: List<TunnelServer>, access: TunnelGameAccess?, includeGame: Boolean): Split {
        val endpoints = access?.endpoints.orEmpty()
        if (endpoints.isEmpty()) return Split(servers, null)
        val identities = endpoints.associateBy { identity(normalizedProtocol(it.protocol), it.address, it.port) }
        val gameAddresses = endpoints.mapTo(mutableSetOf()) { it.address.lowercase(Locale.ROOT) }
        val routes = mutableListOf<GameRoute>()
        val ordinary = servers.filter { server ->
            if (server.engine == TunnelEngineKind.LEVIK_TUIC) {
                // TUIC follows the game host by address (withGameTuicEndpoints); Android Game uses the Xray links.
                return@filter server.tuicConfig?.address?.lowercase(Locale.ROOT) !in gameAddresses
            }
            if (server.engine != TunnelEngineKind.XRAY) return@filter true
            val protocol = (server.outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT)
                ?: return@filter true
            val endpoint = ServerPinger.extractEndpoint(server.outbound) ?: return@filter true
            val match = identities[identity(normalizedProtocol(protocol), endpoint.first, endpoint.second)]
                ?: return@filter true
            val nodeId = match.nodeId?.takeIf { it.matches(NODE_ID) }
            if (nodeId != null && routes.size < MAX_ROUTES && routes.none { it.outbound == server.outbound }) {
                routes += GameRoute(routes.size, nodeId, server.name, server.countryCode, server.outbound)
            }
            false
        }
        if (!includeGame || access?.enabled != true || routes.isEmpty()) return Split(ordinary, null)
        val nodes = nodes(access.nodes, routes)
        val usable = routes.filter { route -> nodes.firstOrNull { it.nodeId == route.nodeId }?.state != "disabled" }
            .mapIndexed { index, route -> route.copy(index = index) }
        if (usable.isEmpty()) return Split(ordinary, null)
        val config = GameServerConfig(
            routes = usable,
            nodes = nodes,
            relayToken = access.relayToken?.takeIf { it.matches(TOKEN) },
            only = access.only,
        )
        val server = TunnelServer(
            id = SERVER_ID,
            tag = SERVER_ID,
            name = SERVER_NAME,
            countryCode = "XX",
            outbound = JsonObject(emptyMap()),
            engine = TunnelEngineKind.LEVIK_GAME,
            category = TunnelServerCategory.REGULAR,
            gameConfig = config,
        )
        return Split(if (access.only) emptyList() else ordinary, server)
    }

    private fun nodes(value: List<GameNode>?, routes: List<GameRoute>): List<GameNode> {
        val seen = mutableSetOf<String>()
        val nodes = value.orEmpty().take(MAX_NODES).mapNotNull { node ->
            val name = node.name.filterNot(Char::isISOControl).trim().take(64)
            if (!node.nodeId.matches(NODE_ID) || !seen.add(node.nodeId) || name.isEmpty() || node.state !in STATES ||
                !node.countryCode.matches(COUNTRY)
            ) return@mapNotNull null
            node.copy(name = name, city = node.city?.filterNot(Char::isISOControl)?.take(64))
        }.toMutableList()
        // A link whose node the profile does not list is never used: the bridge lists every live node.
        routes.forEach { route ->
            if (nodes.none { it.nodeId == route.nodeId }) {
                nodes += GameNode(route.nodeId, route.name.take(64), route.countryCode, state = if (value == null) "enabled" else "disabled")
            }
        }
        return nodes
    }

    private fun identity(protocol: String, address: String, port: Int) = "$protocol:${address.lowercase(Locale.ROOT)}:$port"

    private fun normalizedProtocol(protocol: String): String = when (protocol.lowercase(Locale.ROOT)) {
        "hy2", "hysteria2" -> "hysteria"
        "ss" -> "shadowsocks"
        else -> protocol.lowercase(Locale.ROOT)
    }
}
