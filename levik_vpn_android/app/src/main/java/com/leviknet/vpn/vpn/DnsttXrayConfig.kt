package com.leviknet.vpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** DNS queries use TCP inside the tunnel; unsupported proxied UDP fails closed. */
internal fun dnsttTcpConfig(base: String): String {
    val root = Json.parseToJsonElement(base).jsonObject
    val outbound = root.getValue("outbounds").jsonArray
    val proxyTag = outbound.first().jsonObject.getValue("tag")
    val dns = JsonObject(root.getValue("dns").jsonObject + mapOf(
        "servers" to JsonArray(listOf(JsonPrimitive("tcp://77.88.8.8"), JsonPrimitive("tcp://77.88.8.1"))),
        "tag" to JsonPrimitive("dnstt-dns-query"),
        "disableFallback" to JsonPrimitive(true),
    ))
    val dnsOutbound = buildJsonObject {
        put("tag", "dnstt-dns")
        put("protocol", "dns")
        put("settings", JsonObject(emptyMap()))
    }
    val routing = root.getValue("routing").jsonObject
    val dnsRules = listOf(
        buildJsonObject {
            put("type", "field")
            put("inboundTag", JsonArray(listOf(JsonPrimitive("dnstt-dns-query"))))
            put("outboundTag", proxyTag)
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
        "outbounds" to JsonArray(outbound + dnsOutbound),
        "routing" to JsonObject(routing + ("rules" to JsonArray(dnsRules + routing.getValue("rules").jsonArray + udpBlock))),
    )).toString()
}
