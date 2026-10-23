package com.leviknet.vpn.vpn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ServerProtocol(val label: String) {
    VLESS("VLESS"),
    HYSTERIA("Hysteria 2"),
    TUIC("TUIC"),
    OTHER(""),
}

/** One regular server with the protocols the subscription offers for it. */
data class ServerProtocolGroup(
    /** Ordered VLESS, Hysteria 2, TUIC. A single entry for everything that is not grouped. */
    val variants: List<TunnelServer>,
) {
    val id: String get() = variants.first().id

    /** The variant the card connects with: the selected one, else the preferred protocol. */
    fun active(selectedServerId: String?): TunnelServer =
        variants.firstOrNull { it.id == selectedServerId } ?: variants.first()
}

fun TunnelServer.protocol(): ServerProtocol {
    if (engine == TunnelEngineKind.LEVIK_TUIC) return ServerProtocol.TUIC
    if (engine != TunnelEngineKind.XRAY) return ServerProtocol.OTHER
    return when ((outbound["protocol"] as? JsonPrimitive)?.content?.lowercase()) {
        "vless" -> ServerProtocol.VLESS
        "hysteria", "hysteria2", "hy2" -> ServerProtocol.HYSTERIA
        else -> ServerProtocol.OTHER
    }
}

/**
 * Groups VLESS / Hysteria 2 / TUIC variants of the same regular server (same endpoint
 * host and country). Mobile and allowlist servers (LTE, relay, Yandex) are never
 * grouped, and a second variant of an already present protocol stays separate.
 */
fun groupProtocolVariants(servers: List<TunnelServer>): List<ServerProtocolGroup> {
    val groups = mutableListOf<MutableList<TunnelServer>>()
    val hosts = mutableListOf<String?>()
    servers.forEach { server ->
        val protocol = server.protocol()
        val host = server.endpointHost()?.takeIf {
            protocol != ServerProtocol.OTHER && server.effectiveCategory() == TunnelServerCategory.REGULAR
        }
        val index = if (host == null) -1 else groups.indices.firstOrNull { index ->
            hosts[index] == host &&
                groups[index].first().countryCode.equals(server.countryCode, ignoreCase = true) &&
                groups[index].none { it.protocol() == protocol }
        } ?: -1
        if (index >= 0) {
            groups[index] += server
        } else {
            groups += mutableListOf(server)
            hosts += host
        }
    }
    return groups.map { variants -> ServerProtocolGroup(variants.sortedBy { it.protocol().ordinal }) }
}

/** The host that names this server across the user's apps (FavoriteNodes); null for Yandex. */
fun TunnelServer.syncHost(): String? {
    relayConfig?.let { return it.node.host }
    if (yandexConfig != null) return null
    // Local engines (relay, Yandex) point the outbound at loopback: not a server identity.
    return endpointHost()?.takeUnless { it == "localhost" || it == "::1" || it.startsWith("127.") }
}

/** The port that goes with [syncHost]; null for local engines and outbounds that name none. */
fun TunnelServer.syncPort(): Int? {
    if (relayConfig != null || syncHost() == null) return null
    tuicConfig?.let { return it.port.takeIf(::isPort) }
    val settings = outbound["settings"] as? JsonObject ?: return null
    if ((settings["address"] as? JsonPrimitive)?.content?.isNotBlank() == true) return portOf(settings["port"])
    for (key in listOf("vnext", "servers")) {
        val first = (settings[key] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
        if ((first["address"] as? JsonPrimitive)?.content?.isNotBlank() == true) return portOf(first["port"])
    }
    return null
}

private fun portOf(value: Any?): Int? = (value as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf(::isPort)

private fun isPort(value: Int): Boolean = value in 1..65_535

private fun TunnelServer.endpointHost(): String? {
    tuicConfig?.let { return it.address }
    val settings = outbound["settings"] as? JsonObject ?: return null
    (settings["address"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)?.let { return it.lowercase() }
    for (key in listOf("vnext", "servers")) {
        val first = (settings[key] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
        (first["address"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)?.let { return it.lowercase() }
    }
    return null
}
