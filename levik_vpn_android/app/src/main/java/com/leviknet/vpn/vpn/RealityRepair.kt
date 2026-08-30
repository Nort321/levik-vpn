package com.leviknet.vpn.vpn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Adapts REALITY stream settings produced by libXray's share-link converter to
 * the Xray-core 26.x client schema:
 *
 * - libXray serializes empty server-only fields such as `target` as JSON null;
 *   Xray treats even a null target as a server config and requires serverNames;
 * - the client config must use singular `serverName`; a non-empty legacy
 *   `serverNames` array is rejected with `non-empty "serverNames"`;
 * - links that lack `sni` produce an empty serverName, which the core cannot
 *   accept, so a domain fallback is injected.
 *
 * Every selectable outbound is a client config, so server-only fields are
 * always removed.
 */
internal object RealityRepair {

    fun repair(outbound: JsonObject): JsonObject {
        val stream = outbound["streamSettings"] as? JsonObject ?: return outbound
        val security = (stream["security"] as? JsonPrimitive)?.contentOrNull
            ?.lowercase() ?: return outbound
        if (security != "reality") return outbound
        val reality = stream["realitySettings"] as? JsonObject ?: return outbound

        var updated = reality
        var changed = false

        val fromLegacyArray = firstNonBlankServerName(updated["serverNames"])
        if (fromLegacyArray != null) {
            val currentServerName = updated.serverNameText()
            updated = JsonObject(updated.toMutableMap().apply {
                if (currentServerName.isNullOrBlank()) {
                    put("serverName", JsonPrimitive(fromLegacyArray))
                }
                remove("serverNames")
            })
            changed = true
        } else if ("serverNames" in updated) {
            updated = JsonObject(updated.toMutableMap().apply { remove("serverNames") })
            changed = true
        }

        val withoutServerFields = updated.toMutableMap().apply {
            SERVER_ONLY_FIELDS.forEach(::remove)
        }
        if (withoutServerFields.size != updated.size) {
            updated = JsonObject(withoutServerFields)
            changed = true
        }

        if (updated.serverNameText().isNullOrBlank()) {
            val fallbackServerName = fallbackCandidates(outbound).firstOrNull()
            if (fallbackServerName != null) {
                updated = JsonObject(updated.toMutableMap().apply {
                    put("serverName", JsonPrimitive(fallbackServerName))
                })
                changed = true
            }
        }

        if (!changed) return outbound
        return JsonObject(outbound.toMutableMap().apply {
            put("streamSettings", JsonObject(stream.toMutableMap().apply {
                put("realitySettings", updated)
            }))
        })
    }

    fun hasUsableServerName(reality: JsonObject): Boolean =
        !reality.serverNameText().isNullOrBlank()

    fun fallbackCandidates(outbound: JsonObject): List<String> {
        val stream = outbound["streamSettings"] as? JsonObject
        val candidates = mutableListOf<String>()
        (stream?.get("tlsSettings") as? JsonObject)
            ?.let { (it["serverName"] as? JsonPrimitive)?.contentOrNull }
            ?.let(candidates::add)
        endpointAddress(outbound)
            ?.takeUnless(::looksLikeIpAddress)
            ?.let(candidates::add)
        return candidates.filter(String::isNotBlank).distinct()
    }

    private fun JsonObject.serverNameText(): String? =
        (this["serverName"] as? JsonPrimitive)?.contentOrNull

    private fun firstNonBlankServerName(element: kotlinx.serialization.json.JsonElement?): String? =
        when (element) {
            is JsonArray -> element.firstNotNullOfOrNull { item ->
                (item as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }

    private fun endpointAddress(outbound: JsonObject): String? {
        val settings = outbound["settings"] as? JsonObject ?: return null
        val node = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return null
        return (node["address"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun looksLikeIpAddress(value: String): Boolean =
        value.all { character -> character.isDigit() || character == '.' } ||
            value.contains(':')

    private val SERVER_ONLY_FIELDS = setOf(
        "masterKeyLog",
        "show",
        "target",
        "dest",
        "type",
        "xver",
        "privateKey",
        "minClientVer",
        "maxClientVer",
        "maxTimeDiff",
        "shortIds",
        "mldsa65Seed",
        "limitFallbackUpload",
        "limitFallbackDownload",
    )
}
