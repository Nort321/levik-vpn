package com.leviknet.vpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Inside 198.18.0.0/15, which every other TUN plan leaves outside the VPN. */
internal const val DNSTT_FAKE_IP_POOL = "198.18.0.0/16"

/**
 * Applications get fake A answers at once; the sniffed name reaches the node in the
 * SOCKS request and is resolved there. A tunnelled lookup per name took a stream and
 * several DNS round trips each, and parallel lookups timed out. Unsupported proxied
 * UDP fails closed.
 */
internal fun dnsttTcpConfig(base: String): String {
    val root = Json.parseToJsonElement(base).jsonObject
    val outbound = root.getValue("outbounds").jsonArray
    val proxyTag = outbound.first().jsonObject.getValue("tag")
    val directTag = JsonPrimitive("levik-direct")
    require(outbound.any { it.jsonObject["tag"] == directTag }) { "DNSTT config has no direct outbound" }
    val dns = JsonObject(root.getValue("dns").jsonObject + mapOf(
        // Xray skips fakedns for its own lookups: only direct-routed names reach Yandex,
        // over the same UDP path the DNS tunnel itself uses.
        "servers" to JsonArray(listOf(JsonPrimitive("fakedns")) + listOf("77.88.8.8", "77.88.8.1").map { resolver ->
            buildJsonObject {
                put("address", resolver)
                put("timeoutMs", 5_000)
            }
        }),
        "tag" to JsonPrimitive("dnstt-dns-query"),
        "disableFallback" to JsonPrimitive(true),
    ))
    val dnsOutbound = buildJsonObject {
        put("tag", "dnstt-dns")
        put("protocol", "dns")
        put("settings", buildJsonObject {
            put("rewriteNetwork", "tcp")
            put("rewriteAddress", "77.88.8.8")
            put("rewritePort", 53)
            put("rules", JsonArray(listOf(
                buildJsonObject { put("action", "hijack"); put("qType", 1) },
                // No IPv6 egress; SVCB/HTTPS would cost a tunnel stream per lookup.
                buildJsonObject { put("action", "return"); put("qType", "28,64,65"); put("rCode", 0) },
                buildJsonObject { put("action", "direct") },
            )))
        })
        put("proxySettings", buildJsonObject { put("tag", proxyTag) })
    }
    val routing = root.getValue("routing").jsonObject
    val dnsRules = listOf(
        buildJsonObject {
            put("type", "field")
            put("inboundTag", JsonArray(listOf(JsonPrimitive("dnstt-dns-query"))))
            put("outboundTag", directTag)
        },
        buildJsonObject {
            put("type", "field")
            put("port", "53")
            put("network", "udp,tcp")
            put("outboundTag", "dnstt-dns")
        },
    )
    val udpBlock = buildJsonObject {
        put("type", "field")
        put("network", "udp")
        put("outboundTag", "levik-block")
    }
    return JsonObject(root + mapOf(
        "dns" to dns,
        "fakedns" to JsonArray(listOf(buildJsonObject {
            put("ipPool", DNSTT_FAKE_IP_POOL)
            put("poolSize", 65_535)
        })),
        "outbounds" to JsonArray(outbound + dnsOutbound),
        // The fake address must be replaced by its name before the SOCKS outbound.
        "inbounds" to JsonArray(root.getValue("inbounds").jsonArray.map { inbound ->
            val value = inbound.jsonObject
            val sniffing = value["sniffing"]?.jsonObject ?: return@map inbound
            val protocols = sniffing["destOverride"]?.jsonArray.orEmpty()
            JsonObject(value + ("sniffing" to JsonObject(sniffing + mapOf(
                "routeOnly" to JsonPrimitive(false),
                "destOverride" to JsonArray(listOf(JsonPrimitive("fakedns")) + protocols),
            ))))
        }),
        "routing" to JsonObject(routing + mapOf(
            "domainStrategy" to JsonPrimitive("AsIs"),
            "rules" to JsonArray(dnsRules + routing.getValue("rules").jsonArray + udpBlock),
        )),
    )).toString()
}
