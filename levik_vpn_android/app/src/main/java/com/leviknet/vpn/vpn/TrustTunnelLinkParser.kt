package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.security.DeviceIdentity
import java.net.URI
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal object TrustTunnelLinkParser {
    private val usernamePattern = Regex("[0-9a-f]{32}")
    private val passwordPattern = Regex("[A-Za-z0-9_-]{43}")

    fun parse(source: String): List<TunnelServer> = TuicLinkParser.decodeSource(source).lineSequence()
        .map(String::trim)
        .filter { it.startsWith("levik-trusttunnel://") && it.length <= 1024 }
        .take(128)
        .mapNotNull(::parseLine)
        .distinctBy(TunnelServer::id)
        .toList()

    private fun parseLine(line: String): TunnelServer? = runCatching {
        val link = URI(line)
        val address = link.host ?: return null
        val credentials = link.rawUserInfo?.split(':', limit = 2) ?: return null
        val query = TuicLinkParser.parseQuery(link.rawQuery.orEmpty())
        val mode = query["mode"] ?: return null
        val serverName = query["sni"] ?: return null
        if (link.rawPath.isNotEmpty() || query.keys != setOf("mode", "sni") ||
            credentials.size != 2 || !usernamePattern.matches(credentials[0]) ||
            !passwordPattern.matches(credentials[1]) || !TuicLinkParser.IPV4.matches(address) ||
            link.port !in 1..65535 || !TuicLinkParser.DNS_NAME.matches(serverName) ||
            mode !in setOf("h2", "h3")) return null
        val name = link.rawFragment?.let(TuicLinkParser::decode).orEmpty()
        val id = DeviceIdentity.sha256Hex("trusttunnel:$address:${link.port}:$mode:${credentials[0]}".encodeToByteArray())
        val tag = "levik-trusttunnel-${id.take(12)}"
        TunnelServer(
            id = id,
            tag = tag,
            name = TuicLinkParser.stripLeadingFlags(name).take(96).ifBlank { "TrustTunnel ${mode.uppercase()}" },
            countryCode = TuicLinkParser.countryCodeFromFlag(name) ?: "XX",
            outbound = buildJsonObject {
                put("tag", tag)
                put("protocol", "trusttunnel-$mode")
                putJsonObject("settings") {
                    put("address", address)
                    put("port", link.port)
                }
            },
            engine = TunnelEngineKind.LEVIK_TRUSTTUNNEL,
            category = TunnelServerCategory.REGULAR,
            trustTunnelConfig = TrustTunnelServerConfig(address, link.port, credentials[0], credentials[1], serverName, mode == "h3"),
        )
    }.getOrNull()
}
