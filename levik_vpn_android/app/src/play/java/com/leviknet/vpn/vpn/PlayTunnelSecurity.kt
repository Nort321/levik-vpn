package com.leviknet.vpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Validate the actual generated config, including automatic fallback outbounds. */
internal object PlayTunnelSecurity {
    private val vmessCiphers = setOf("auto", "aes-128-gcm", "chacha20-poly1305")
    private val shadowsocksCiphers = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    )

    fun validate(config: String) {
        val root = Json.parseToJsonElement(config) as? JsonObject
            ?: throw IllegalArgumentException("Invalid VPN configuration")
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("Missing VPN outbounds")
        require(outbounds.isNotEmpty()) { "Missing VPN outbounds" }
        outbounds.forEach { element ->
            val outbound = element as? JsonObject
                ?: throw IllegalArgumentException("Invalid VPN outbound")
            val protocol = outbound.text("protocol")
            // These are app-generated local routing decisions, not VPN tunnel endpoints.
            if (protocol in setOf("freedom", "blackhole", "dns")) return@forEach
            val stream = outbound["streamSettings"] as? JsonObject
            require(!hasInsecureVerification(outbound)) { "VPN certificate verification is required" }
            val encryptedTransport = stream?.text("security") in setOf("tls", "reality")
            val settings = outbound["settings"] as? JsonObject
            val encryptedProtocol = when (protocol) {
                "vmess" -> settings != null && vmessEncrypted(settings)
                "shadowsocks" -> settings != null && shadowsocksEncrypted(settings)
                else -> false
            }
            require(encryptedTransport || encryptedProtocol) {
                "Google Play VPN tunnels require encryption to the endpoint"
            }
        }
    }

    private fun vmessEncrypted(settings: JsonObject): Boolean {
        val servers = settings["vnext"] as? JsonArray
            ?: return (settings.text("security") ?: "auto") in vmessCiphers
        return servers.isNotEmpty() && servers.all { server ->
            val users = (server as? JsonObject)?.get("users") as? JsonArray
            users != null && users.isNotEmpty() && users.all { user ->
                user is JsonObject && (user.text("security") ?: "auto") in vmessCiphers
            }
        }
    }

    private fun shadowsocksEncrypted(settings: JsonObject): Boolean {
        val servers = settings["servers"] as? JsonArray
            ?: return settings.text("method") in shadowsocksCiphers
        return servers.isNotEmpty() && servers.all { server ->
            server is JsonObject && server.text("method") in shadowsocksCiphers
        }
    }

    private fun hasInsecureVerification(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (name, value) ->
            (name in setOf("allowInsecure", "insecure", "skipCertVerify") &&
                (value as? JsonPrimitive)?.contentOrNull != "false") || hasInsecureVerification(value)
        }
        is JsonArray -> element.any(::hasInsecureVerification)
        else -> false
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull?.lowercase()
}
