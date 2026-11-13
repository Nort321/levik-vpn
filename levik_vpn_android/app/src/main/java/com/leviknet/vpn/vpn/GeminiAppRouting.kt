package com.leviknet.vpn.vpn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep HTTP/3 fallback local to Gemini, without changing browser or Google Search traffic. */
internal data class GeminiAppRouting(
    val geminiUid: Int? = null,
    val googleAppUid: Int? = null,
) {
    fun apply(config: JsonObject): JsonObject {
        val rules = buildList {
            geminiUid?.takeIf { it > 0 && it != googleAppUid }?.let { add(blockQuic(it)) }
            googleAppUid?.takeIf { it > 0 }?.let { uid ->
                add(JsonObject(blockQuic(uid).toMutableMap().apply {
                    put("domain", JsonArray(BACKEND_DOMAINS.map(::JsonPrimitive)))
                }))
            }
        }
        if (rules.isEmpty()) return config
        val routing = config["routing"] as? JsonObject ?: return config
        val existing = routing["rules"] as? JsonArray ?: return config
        return JsonObject(config.toMutableMap().apply {
            put("routing", JsonObject(routing.toMutableMap().apply {
                put("rules", JsonArray(rules + existing))
            }))
        })
    }

    private fun blockQuic(uid: Int): JsonObject = buildJsonObject {
        put("type", "field")
        put("inboundTag", JsonArray(listOf(JsonPrimitive("levik-tun-in"))))
        // libXray's Android process finder returns the numeric UID as process name.
        put("process", JsonArray(listOf(JsonPrimitive(uid.toString()))))
        put("network", "udp")
        put("port", "443")
        put("outboundTag", "levik-block")
    }

    companion object {
        private val BACKEND_DOMAINS = listOf(
            "domain:gemini.google", "domain:gemini.google.com", "domain:gemini.gstatic.com",
            "full:generativelanguage.googleapis.com", "full:proactivebackend-pa.googleapis.com",
            "full:robinfrontend-pa.googleapis.com", "full:geller-pa.googleapis.com",
            "full:geminiweb-pa.clients.google.com", "full:geminiweb-pa.clients6.google.com",
        )
    }
}
