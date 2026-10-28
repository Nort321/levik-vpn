package com.leviknet.vpn.vpn

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DnsttServerConfig(
    val domain: String,
    val publicKey: String,
    val username: String,
    val password: String,
    val resolver: String,
    val qps: Int = 40,
)

/** Only the encrypted Levik profile issues these links. Resolvers are pinned to Yandex Basic. */
object DnsttLinkParser {
    val RESOLVERS = setOf("77.88.8.8", "77.88.8.1")

    /** The native Xray converter must never receive our app-only URI scheme. */
    fun withoutLinks(source: TunnelProfileSource): TunnelProfileSource? {
        val encoded = !source.content.contains("://")
        val text = if (!encoded) source.content else runCatching {
            Base64.getDecoder().decode(source.content.filterNot(Char::isWhitespace)).decodeToString(throwOnInvalidSequence = true)
        }.getOrDefault(source.content)
        if (text.lineSequence().none { it.trim().startsWith("dnstt://") }) return source
        val clean = text.lineSequence().filterNot { it.trim().startsWith("dnstt://") }.joinToString("\n")
        if (clean.isBlank()) return null
        return source.copy(content = if (encoded) Base64.getEncoder().encodeToString(clean.toByteArray()) else clean)
    }

    fun parse(source: String): List<TunnelServer> {
        val text = if (source.contains("://")) source else runCatching {
            Base64.getDecoder().decode(source.filterNot(Char::isWhitespace)).decodeToString(throwOnInvalidSequence = true)
        }.getOrDefault(source)
        return text.lineSequence().map(String::trim).filter { it.startsWith("dnstt://") }
            .take(2).mapNotNull(::parseLine).distinctBy(TunnelServer::id).toList()
    }

    internal fun parseLine(line: String): TunnelServer? = runCatching {
        require(line.length <= 2048)
        val uri = URI(line)
        require(uri.scheme == "dnstt" && uri.port == -1 && uri.path.isNullOrEmpty())
        val domain = requireNotNull(uri.host).lowercase()
        require(domain.matches(Regex("(?=.{1,100}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")))
        val credentials = requireNotNull(uri.rawUserInfo).split(':')
        require(credentials.size == 2)
        val username = credentials[0]
        val password = credentials[1]
        require(username.matches(Regex("[0-9a-f]{32}")) && password.matches(Regex("[A-Za-z0-9_-]{32}")))
        val entries = uri.rawQuery.orEmpty().split('&').map { part ->
            val pair = part.split('=', limit = 2)
            require(pair.size == 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        require(entries.map { it.first }.distinct().size == entries.size)
        val query = entries.toMap()
        require(query.keys == setOf("pubkey", "resolver", "qps"))
        val key = query.getValue("pubkey")
        val resolver = query.getValue("resolver")
        val qps = query.getValue("qps").toInt()
        require(key.matches(Regex("[0-9a-f]{64}")) && key.any { it != '0' })
        require(resolver in RESOLVERS && qps in 20..80)
        val id = "dnstt:$domain:$resolver"
        TunnelServer(
            id = id, tag = id, name = if (resolver == "77.88.8.8") "DNS · Яндекс" else "DNS · Яндекс резерв",
            countryCode = "DE", outbound = JsonObject(emptyMap()), engine = TunnelEngineKind.LEVIK_DNSTT,
            category = TunnelServerCategory.DNS_TEST,
            dnsttConfig = DnsttServerConfig(domain, key, username, password, resolver, qps),
        )
    }.getOrNull()
}
