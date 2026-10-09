package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.security.DeviceIdentity
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Parses TUIC v5 links issued by the Levik profile service. libXray skips these
 * lines, so they are converted here into Direct-only sidecar servers.
 *
 * Only IP-literal servers with a pinned CA (`levik_ca`, base64url DER) are accepted;
 * anything else is skipped rather than connected insecurely.
 */
object TuicLinkParser {
    fun parse(source: String): List<TunnelServer> {
        val text = decodeSource(source)
        return text.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("tuic://", ignoreCase = true) }
            .take(MAX_SERVERS)
            .mapNotNull(::parseLine)
            .distinctBy(TunnelServer::id)
            .toList()
    }

    internal fun parseLine(line: String): TunnelServer? = runCatching {
        val uri = URI(line)
        val userInfo = uri.rawUserInfo ?: return null
        val separator = userInfo.indexOf(':')
        if (separator <= 0) return null
        val uuid = decode(userInfo.substring(0, separator)).lowercase()
        val password = decode(userInfo.substring(separator + 1))
        val address = uri.host ?: return null
        val port = if (uri.port == -1) 443 else uri.port
        val query = parseQuery(uri.rawQuery.orEmpty())
        val serverName = query["sni"].orEmpty().lowercase()
        val alpn = (query["alpn"] ?: "h3").split(',').filter(String::isNotEmpty)
        val congestionControl = query["congestion_control"] ?: "bbr"
        val udpRelayMode = query["udp_relay_mode"] ?: "native"
        val caPem = pinnedCertificatePem(query["levik_ca"]) ?: return null
        if (!uuid.matches(UUID) || !password.matches(PASSWORD) || !address.matches(IPV4) ||
            port !in 1..65_535 || !serverName.matches(DNS_NAME) ||
            congestionControl !in CONGESTION_CONTROLS || udpRelayMode !in UDP_RELAY_MODES ||
            alpn.isEmpty() || alpn.size > 4 || alpn.any { !it.matches(ALPN) }
        ) return null

        val remark = uri.rawFragment?.let(::decode).orEmpty()
        val id = DeviceIdentity.sha256Hex("tuic:$address:$port:$uuid".encodeToByteArray())
        val tag = "levik-tuic-${id.take(10)}"
        TunnelServer(
            id = id,
            tag = tag,
            name = stripLeadingFlags(remark).ifBlank { "TUIC" }.take(MAX_NAME_LENGTH),
            countryCode = countryCodeFromFlag(remark) ?: "XX",
            // Descriptive endpoint for pings and grouping; never handed to Xray as-is.
            outbound = buildJsonObject {
                put("tag", tag)
                put("protocol", "tuic")
                put("settings", buildJsonObject {
                    put("address", address)
                    put("port", port)
                })
            },
            engine = TunnelEngineKind.LEVIK_TUIC,
            category = TunnelServerCategory.REGULAR,
            tuicConfig = TuicServerConfig(
                address = address,
                port = port,
                uuid = uuid,
                password = password,
                serverName = serverName,
                alpn = alpn,
                congestionControl = congestionControl,
                udpRelayMode = udpRelayMode,
                caCertificatePem = caPem,
            ),
        )
    }.getOrNull()

    /** The sidecar endpoint shown and pinged for a TUIC server. */
    fun endpointHost(server: TunnelServer): String? =
        server.tuicConfig?.address ?: (server.outbound["settings"] as? kotlinx.serialization.json.JsonObject)
            ?.get("address")?.let { (it as? JsonPrimitive)?.content }

    private fun decodeSource(source: String): String {
        val trimmed = source.trim()
        if (trimmed.contains("://") || trimmed.startsWith("{")) return trimmed
        val compact = trimmed.filterNot(Char::isWhitespace)
        return sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder())
            .mapNotNull { decoder -> runCatching { decoder.decode(compact).decodeToString(throwOnInvalidSequence = true) }.getOrNull() }
            .firstOrNull { it.contains("://") }
            ?: trimmed
    }

    private fun pinnedCertificatePem(encoded: String?): String? {
        if (encoded.isNullOrEmpty() || encoded.length > MAX_CA_LENGTH || !encoded.matches(BASE64URL)) return null
        val der = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrNull() ?: return null
        if (certificate.basicConstraints < 0) return null
        val body = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&')
        .filter(String::isNotEmpty)
        .associate { part ->
            val index = part.indexOf('=')
            if (index < 0) decode(part) to "" else decode(part.substring(0, index)) to decode(part.substring(index + 1))
        }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)

    private fun stripLeadingFlags(value: String): String {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (codePoint in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END || Character.isWhitespace(codePoint)) {
                index += Character.charCount(codePoint)
            } else {
                break
            }
        }
        return value.substring(index).trim()
    }

    private fun countryCodeFromFlag(value: String): String? {
        val codePoints = value.codePoints().toArray()
        for (index in 0 until codePoints.lastIndex) {
            val first = codePoints[index]
            val second = codePoints[index + 1]
            if (first in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END &&
                second in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END
            ) {
                return buildString(2) {
                    append('A' + (first - REGIONAL_INDICATOR_START))
                    append('A' + (second - REGIONAL_INDICATOR_START))
                }
            }
        }
        return null
    }

    private const val MAX_SERVERS = 64
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_CA_LENGTH = 8_192
    private const val REGIONAL_INDICATOR_START = 0x1F1E6
    private const val REGIONAL_INDICATOR_END = 0x1F1FF
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val PASSWORD = Regex("[A-Za-z0-9_-]{16,128}")
    private val IPV4 = Regex("(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)")
    private val DNS_NAME = Regex("(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
    private val ALPN = Regex("[A-Za-z0-9./-]{1,32}")
    private val BASE64URL = Regex("[A-Za-z0-9_-]+")
    private val CONGESTION_CONTROLS = setOf("bbr", "cubic", "new_reno")
    private val UDP_RELAY_MODES = setOf("native", "quic")
}
