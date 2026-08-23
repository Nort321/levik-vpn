package com.leviknet.vpn.vpn

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compatibility bridge for subscriptions generated before XHTTP renamed its
 * session fields. It only copies fields inside VLESS XHTTP `extra` objects and
 * leaves legacy keys in place. `enableXmux` is deliberately never translated.
 */
class LegacyXhttpNormalizer(
    private val json: Json,
) {
    internal fun isFullXrayConfig(source: String): Boolean =
        parseXrayConfig(source) != null ||
            decodeBase64Blob(source)?.let(::parseXrayConfig) != null

    fun normalize(source: String): String {
        normalizeXrayJson(source)?.let { return it }

        val normalizedLinks = normalizeLinks(source)
        if (normalizedLinks != source) return normalizedLinks

        decodeBase64Blob(source)?.let { decoded ->
            val normalizedDecoded = normalizeXrayJson(decoded) ?: normalizeLinks(decoded)
            if (normalizedDecoded != decoded) {
                return encodeLikeSource(source, normalizedDecoded)
            }
        }
        return source
    }

    private fun normalizeLinks(source: String): String =
        VLESS_LINK.replace(source) { match ->
            normalizeVlessLink(match.value)
        }

    private fun normalizeVlessLink(link: String): String {
        val fragmentIndex = link.indexOf('#')
        val fragment = if (fragmentIndex >= 0) link.substring(fragmentIndex) else ""
        val withoutFragment = if (fragmentIndex >= 0) link.substring(0, fragmentIndex) else link
        val queryIndex = withoutFragment.indexOf('?')
        if (queryIndex < 0) return link

        val prefix = withoutFragment.substring(0, queryIndex + 1)
        val parts = withoutFragment.substring(queryIndex + 1).split('&').toMutableList()
        val network = parts.firstValue("type") ?: parts.firstValue("network")
        if (!network.equals("xhttp", ignoreCase = true) &&
            !network.equals("splithttp", ignoreCase = true)
        ) {
            return link
        }

        val extraIndex = parts.indexOfFirst { raw ->
            raw.substringBefore('=').equals("extra", ignoreCase = true)
        }
        if (extraIndex < 0) return link

        val rawPart = parts[extraIndex]
        val separator = rawPart.indexOf('=')
        if (separator < 0) return link
        val encodedExtra = rawPart.substring(separator + 1)
        val decodedExtra = runCatching { percentDecode(encodedExtra) }.getOrNull() ?: return link
        val extraObject = runCatching {
            json.parseToJsonElement(decodedExtra).jsonObject
        }.getOrNull() ?: return link
        val normalized = normalizeExtraObject(extraObject)
        if (normalized == extraObject) return link

        val normalizedJson = json.encodeToString(JsonObject.serializer(), normalized)
        parts[extraIndex] = rawPart.substring(0, separator + 1) + percentEncode(normalizedJson)
        return prefix + parts.joinToString("&") + fragment
    }

    private fun List<String>.firstValue(name: String): String? {
        val raw = firstOrNull { part ->
            part.substringBefore('=').equals(name, ignoreCase = true)
        } ?: return null
        return runCatching { percentDecode(raw.substringAfter('=', "")) }.getOrNull()
    }

    private fun normalizeXrayJson(source: String): String? {
        val root = parseXrayConfig(source) ?: return null
        val outbounds = root.getValue("outbounds") as JsonArray
        var changed = false
        val normalizedOutbounds = outbounds.map { element ->
            val outbound = element as? JsonObject ?: return@map element
            if (outbound["protocol"]?.jsonPrimitive?.contentOrNull != "vless") {
                return@map outbound
            }
            val stream = outbound["streamSettings"] as? JsonObject ?: return@map outbound
            val network = stream["network"]?.jsonPrimitive?.contentOrNull
            if (network != "xhttp" && network != "splithttp") return@map outbound
            val settingsKey = if ("xhttpSettings" in stream) "xhttpSettings" else "splithttpSettings"
            val settings = stream[settingsKey] as? JsonObject ?: return@map outbound
            val normalizedSettings = normalizeExtraObject(settings)
            if (normalizedSettings == settings) return@map outbound
            changed = true
            JsonObject(outbound.toMutableMap().apply {
                put("streamSettings", JsonObject(stream.toMutableMap().apply {
                    put(settingsKey, normalizedSettings)
                }))
            })
        }
        if (!changed) return null
        val normalizedRoot = JsonObject(root.toMutableMap().apply {
            put("outbounds", JsonArray(normalizedOutbounds))
        })
        return json.encodeToString(JsonObject.serializer(), normalizedRoot)
    }

    private fun parseXrayConfig(source: String): JsonObject? {
        val trimmed = source.trim()
        if (!trimmed.startsWith('{')) return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return null
        return root.takeIf { it["outbounds"] is JsonArray }
    }

    private fun normalizeExtraObject(source: JsonObject): JsonObject {
        var changed = false
        val mapped = source.mapValues { (_, value) ->
            when (value) {
                is JsonObject -> normalizeExtraObject(value).also {
                    if (it != value) changed = true
                }
                is JsonArray -> JsonArray(value.map { item ->
                    if (item is JsonObject) {
                        normalizeExtraObject(item).also {
                            if (it != item) changed = true
                        }
                    } else {
                        item
                    }
                })
                else -> value
            }
        }.toMutableMap()

        if ("sessionIDPlacement" !in mapped) {
            mapped["sessionPlacement"]?.let { legacy ->
                mapped["sessionIDPlacement"] = legacy
                changed = true
            }
        }
        if ("sessionIDKey" !in mapped) {
            mapped["sessionKey"]?.let { legacy ->
                mapped["sessionIDKey"] = legacy
                changed = true
            }
        }
        return if (changed) JsonObject(mapped) else source
    }

    private fun decodeBase64Blob(source: String): String? {
        val compact = source.filterNot(Char::isWhitespace)
        if (compact.length < 8 || !compact.matches(BASE64_TEXT)) return null
        return sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder())
            .mapNotNull { decoder ->
                runCatching {
                    decoder.decode(compact).decodeToString(throwOnInvalidSequence = true)
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun encodeLikeSource(original: String, normalized: String): String {
        val baseEncoder = if ('-' in original || '_' in original) {
            Base64.getUrlEncoder()
        } else {
            Base64.getEncoder()
        }
        val encoder = if (original.trimEnd().endsWith("=")) {
            baseEncoder
        } else {
            baseEncoder.withoutPadding()
        }
        return encoder.encodeToString(normalized.encodeToByteArray())
    }

    private fun percentDecode(value: String): String {
        val output = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                require(index + 2 < value.length) { "Truncated percent escape" }
                val high = value[index + 1].digitToIntOrNull(16)
                val low = value[index + 2].digitToIntOrNull(16)
                require(high != null && low != null) { "Invalid percent escape" }
                output.write((high shl 4) or low)
                index += 3
            } else {
                output.write(character.toString().encodeToByteArray())
                index += 1
            }
        }
        return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private fun percentEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (unsigned in 'A'.code..'Z'.code ||
                unsigned in 'a'.code..'z'.code ||
                unsigned in '0'.code..'9'.code ||
                character in UNRESERVED
            ) {
                append(character)
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    companion object {
        private val VLESS_LINK = Regex("vless://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
        private val BASE64_TEXT = Regex("[A-Za-z0-9_+/=-]+")
        private val UNRESERVED = setOf('-', '.', '_', '~')
        private const val HEX = "0123456789ABCDEF"
    }
}
