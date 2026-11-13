package com.leviknet.vpn.vpn

import java.net.InetAddress
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

const val EXTERNAL_AUTO_ID = "levik-external-auto"

@Serializable
data class ExternalServerPool(
    val version: Int,
    val sequence: String,
    val updatedAt: String,
    val servers: List<TunnelServer>,
) {
    fun validated(): ExternalServerPool {
        require(version == 1 && sequence.matches(Regex("[0-9]{1,20}"))) { "Invalid external catalog" }
        Instant.parse(updatedAt)
        require(servers.size <= 10_000) { "External catalog is too large" }
        val seen = mutableSetOf<String>()
        return copy(servers = servers.filter { server ->
            val outbound = server.outbound
            val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull
            val settings = outbound["settings"] as? JsonObject
            val endpoints = settings?.get(if (protocol == "vless" || protocol == "vmess") "vnext" else "servers") as? JsonArray
            val endpoint = endpoints?.singleOrNull() as? JsonObject
            val address = (endpoint?.get("address") as? JsonPrimitive)?.contentOrNull
            val port = (endpoint?.get("port") as? JsonPrimitive)?.intOrNull
            server.id.matches(Regex("external:[a-f0-9]{64}")) && server.tag == "external-node-" + server.id.removePrefix("external:") &&
                (outbound["tag"] as? JsonPrimitive)?.contentOrNull == server.tag && server.engine == TunnelEngineKind.XRAY &&
                protocol in setOf("vless", "vmess", "trojan", "shadowsocks") &&
                outbound.keys.all { it in setOf("tag", "protocol", "settings", "streamSettings") } && safeFields(outbound) &&
                address != null && publicExternalAddress(address) && port != null && port in 1..65535 && seen.add(server.id)
        }.mapIndexed { index, server -> server.copy(name = "Безлимитный маршрут ${index + 1}", countryCode = "XX") })
    }
}

private fun safeFields(value: kotlinx.serialization.json.JsonElement): Boolean = when (value) {
    is JsonArray -> value.all(::safeFields)
    is JsonObject -> value.all { (key, child) ->
        key !in setOf("sendThrough", "proxySettings", "dialerProxy", "sockopt", "fragment", "finalmask", "inbounds", "routing") &&
            (key != "allowInsecure" || child.toString() == "false") && safeFields(child)
    }
    is JsonPrimitive -> value.content.length <= 4096 && value.content.none { it.code < 32 || it.code == 127 }
}

/** Numeric, globally routable endpoints only: imported DNS cannot be rebound to a user's LAN. */
internal fun publicExternalAddress(value: String): Boolean {
    if (!value.matches(Regex("[0-9a-fA-F:.]{2,45}"))) return false
    val address = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return false
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 16) {
        val first = bytes[0] * 256 + bytes[1]
        val second = bytes[2] * 256 + bytes[3]
        return bytes[0] in 0x20..0x3f && first != 0x2002 &&
            !(first == 0x2001 && (second < 0x200 || second == 0xdb8)) && !(first == 0x3fff && second < 0x1000)
    }
    val (a, b, c) = bytes
    return a in 1..223 && !(a == 100 && b in 64..127) &&
        !(a == 192 && (b == 0 && c in setOf(0, 2) || b == 88 && c == 99)) &&
        !(a == 198 && (b in setOf(18, 19) || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113)
}

fun externalAutoServer() = TunnelServer(
    id = EXTERNAL_AUTO_ID, tag = EXTERNAL_AUTO_ID, name = "Безлимитные серверы", countryCode = "XX",
    category = TunnelServerCategory.MOBILE_ALLOWLIST,
    outbound = JsonObject(mapOf("tag" to JsonPrimitive(EXTERNAL_AUTO_ID), "protocol" to JsonPrimitive("blackhole"))),
)

data class ExternalPoolProgress(val checked: Int, val total: Int)

/** Return on the first completed transfer; pending checks are cancelled before the tunnel starts. */
internal suspend fun firstVerifiedExternalServer(
    servers: List<TunnelServer>,
    verify: suspend (TunnelServer) -> Boolean,
    onChecked: (TunnelServer) -> Unit,
): TunnelServer? = supervisorScope {
    val answers = Channel<Pair<TunnelServer, Boolean>>(Channel.UNLIMITED)
    val jobs = servers.map { server -> launch {
        val passed = try { verify(server) } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
        answers.send(server to passed)
    } }
    try {
        repeat(servers.size) {
            val (server, passed) = answers.receive()
            onChecked(server)
            if (passed) return@supervisorScope server
        }
        null
    } finally { jobs.forEach { it.cancel() } }
}

internal fun withoutExternalPool(profile: PreparedTunnelProfile): PreparedTunnelProfile = profile.copy(
    externalPoolAllowed = false, externalPool = null,
    servers = profile.servers.filter { it.id != EXTERNAL_AUTO_ID && !it.id.startsWith("external:") },
)

internal fun enforceExternalPoolAccess(profile: PreparedTunnelProfile): PreparedTunnelProfile =
    if (!profile.externalPoolAllowed || profile.gameOnly) withoutExternalPool(profile) else profile

internal fun retainExternalPool(incoming: PreparedTunnelProfile, previous: PreparedTunnelProfile?): PreparedTunnelProfile =
    if (!incoming.externalPoolAllowed || incoming.gameOnly) withoutExternalPool(incoming)
    else if (incoming.version == 1 && incoming.externalPool?.servers.isNullOrEmpty() &&
        previous?.externalPoolAllowed == true && previous.subscriptionId == incoming.subscriptionId && !previous.externalPool?.servers.isNullOrEmpty()) {
        incoming.copy(externalPool = previous.externalPool,
            servers = incoming.servers.filter { it.id != EXTERNAL_AUTO_ID } + externalAutoServer())
    } else incoming
