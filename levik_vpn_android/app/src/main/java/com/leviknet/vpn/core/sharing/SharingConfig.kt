package com.leviknet.vpn.core.sharing

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.io.File
import java.util.UUID
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** In-memory credentials disappear when sharing stops, the process exits or the session expires. */
class SharingHostSession(val invitation: SharingInvitation, val proxyPort: Int, val controlPath: String) {
    fun snapshot(ready: Boolean = false) = SharingHostState(invitation.host, proxyPort, invitation.port, invitation.encode(), invitation.expiresAt, ready)
    override fun toString(): String = "SharingHostSession(expiresAt=${invitation.expiresAt})"

    companion object {
        fun create(address: String, name: String, directory: File, nowMs: Long = System.currentTimeMillis()): SharingHostSession {
            require(address in SharingNetworks.localAddresses()) { "Адрес точки доступа изменился" }
            val guest = freeLocalPort(address, udp = true)
            var proxy = freeLocalPort(address)
            while (proxy == guest) proxy = freeLocalPort(address)
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            return SharingHostSession(SharingInvitation(host = address, port = guest,
                password = Base64.getUrlEncoder().withoutPadding().encodeToString(key),
                name = name.filterNot(Char::isISOControl).trim().take(64).ifBlank { "Телефон Levik" },
                expiresAt = nowMs + SharingInvitation.MAX_LIFETIME_MS), proxy,
                File(directory, "sharing-${UUID.randomUUID().toString().take(8)}.sock").absolutePath)
        }
    }
}

/** Reserve/check only the selected interface, never 0.0.0.0 or a cellular interface. */
fun freeLocalPort(address: String = "127.0.0.1", udp: Boolean = false): Int {
    repeat(8) {
        ServerSocket().use { tcp ->
            tcp.reuseAddress = false
            tcp.bind(InetSocketAddress(InetAddress.getByName(address), 0))
            if (!udp) return tcp.localPort
            val usable = runCatching { DatagramSocket(null).use { it.bind(InetSocketAddress(address, tcp.localPort)) } }.isSuccess
            if (usable) return tcp.localPort
        }
    }
    error("Не удалось открыть локальный порт раздачи")
}

object SharingConfig {
    const val HTTP_IN = "levik-share-http"
    const val GUEST_IN = "levik-share-guest"
    private const val DNS_TAG = "levik-share-dns"
    private const val BLOCK = "levik-share-block"
    private const val GUEST_OUT = "levik-share-phone"
    private const val API_IN = "levik-share-api-in"
    private val PRIVATE = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "::/0")

    /** Applied after ServiceRoutes/SmartAuto so guest rules have first priority and no direct exit. */
    fun host(base: String, session: SharingHostSession, vpnTag: String, json: Json): String {
        val root = json.parseToJsonElement(base).jsonObject
        val outbounds = root.getValue("outbounds").jsonArray
        val vpn = outbounds.firstOrNull { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == vpnTag }?.jsonObject
            ?: error("Выбранный VPN-выход недоступен для раздачи")
        require(vpn["protocol"]?.jsonPrimitive?.contentOrNull !in setOf("freedom", "blackhole", null)) { "Раздача требует VPN-выход" }
        val invite = session.invitation
        require(session.controlPath.startsWith('/') && session.controlPath.toByteArray().size < 100 && !session.controlPath.contains('\u0000'))
        val previousApi = root["api"] as? JsonObject
        val apiTag = previousApi?.get("tag")?.jsonPrimitive?.contentOrNull ?: "levik-share-api"
        val extraInbounds = buildJsonArray {
            add(buildJsonObject {
                put("tag", HTTP_IN); put("listen", invite.host); put("port", session.proxyPort); put("protocol", "http")
                put("settings", buildJsonObject { put("allowTransparent", false) })
            })
            add(buildJsonObject {
                put("tag", GUEST_IN); put("listen", invite.host); put("port", invite.port); put("protocol", "shadowsocks")
                put("settings", buildJsonObject {
                    put("method", invite.method); put("password", invite.password); put("network", "tcp,udp")
                })
            })
            add(buildJsonObject {
                put("tag", API_IN); put("listen", "${session.controlPath},0600"); put("protocol", "dokodemo-door")
                put("settings", buildJsonObject { put("address", "127.0.0.1") })
            })
        }
        val guestTags = JsonArray(listOf(HTTP_IN, GUEST_IN).map(::JsonPrimitive))
        val priority = buildJsonArray {
            add(buildJsonObject { put("type", "field"); put("inboundTag", JsonArray(listOf(JsonPrimitive(API_IN)))); put("outboundTag", apiTag) })
            add(buildJsonObject { put("type", "field"); put("inboundTag", guestTags); put("ip", JsonArray(PRIVATE.map(::JsonPrimitive))); put("outboundTag", BLOCK) })
            add(buildJsonObject { put("type", "field"); put("inboundTag", guestTags); put("domain", JsonArray(listOf("full:localhost", "domain:localhost", "domain:local").map(::JsonPrimitive))); put("outboundTag", BLOCK) })
            add(buildJsonObject { put("type", "field"); put("inboundTag", guestTags); put("port", "25"); put("outboundTag", BLOCK) })
            add(buildJsonObject { put("type", "field"); put("inboundTag", guestTags); put("network", "tcp,udp"); put("outboundTag", vpnTag) })
            // Resolution for the private-destination check must not leave through a direct rule.
            add(buildJsonObject { put("type", "field"); put("inboundTag", JsonArray(listOf(JsonPrimitive(DNS_TAG)))); put("outboundTag", vpnTag) })
        }
        val routing = root["routing"]?.jsonObject ?: buildJsonObject { }
        val dns = root["dns"]?.jsonObject ?: buildJsonObject { put("servers", JsonArray(listOf(JsonPrimitive("1.1.1.1")))) }
        val policy = root["policy"] as? JsonObject ?: buildJsonObject { }
        val systemPolicy = policy["system"] as? JsonObject ?: buildJsonObject { }
        val services = (previousApi?.get("services") as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet() + "StatsService"
        val result = JsonObject(root + mapOf(
            "inbounds" to JsonArray(root.getValue("inbounds").jsonArray + extraInbounds),
            "api" to buildJsonObject { put("tag", apiTag); put("services", JsonArray(services.map(::JsonPrimitive))) },
            "stats" to buildJsonObject { },
            "policy" to JsonObject(policy + ("system" to JsonObject(systemPolicy + mapOf("statsInboundUplink" to JsonPrimitive(true), "statsInboundDownlink" to JsonPrimitive(true))))),
            "outbounds" to JsonArray(outbounds + buildJsonObject { put("tag", BLOCK); put("protocol", "blackhole") }),
            "dns" to JsonObject(dns + mapOf("tag" to JsonPrimitive(DNS_TAG), "servers" to JsonArray(
                dns.getValue("servers").jsonArray.map { resolver ->
                    if (resolver is JsonObject && resolver["address"]?.jsonPrimitive?.contentOrNull != "localhost")
                        JsonObject(resolver + ("tag" to JsonPrimitive(DNS_TAG))) else resolver
                },
            ))),
            "routing" to JsonObject(routing + mapOf("domainStrategy" to JsonPrimitive("IPOnDemand"), "rules" to JsonArray(priority + (routing["rules"]?.jsonArray ?: JsonArray(emptyList()))))),
            "log" to buildJsonObject { put("loglevel", "none") },
        ))
        return json.encodeToString(JsonObject.serializer(), result)
    }

    /** Independent profile: account routing, bypass lists and server selection never apply here. */
    fun guest(invitation: SharingInvitation, tunFd: Int, healthPort: Int, json: Json): String {
        require(tunFd >= 0 && healthPort in 1..65535)
        val config = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "none") })
            put("env", buildJsonObject { put("xray.tun.fd", tunFd.toString()) })
            put("dns", buildJsonObject { put("queryStrategy", "UseIPv4"); put("servers", JsonArray(listOf(JsonPrimitive("1.1.1.1"), JsonPrimitive("8.8.8.8")))) })
            put("inbounds", buildJsonArray {
                add(buildJsonObject { put("tag", "tun-in"); put("protocol", "tun"); put("port", 0); put("settings", buildJsonObject { put("name", "levik0"); put("mtu", 1400) }) })
                add(buildJsonObject { put("tag", "guest-health"); put("listen", "127.0.0.1"); put("port", healthPort); put("protocol", "http"); put("settings", buildJsonObject { }) })
            })
            put("outbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", GUEST_OUT); put("protocol", "shadowsocks")
                    put("settings", buildJsonObject { put("servers", buildJsonArray { add(buildJsonObject {
                        put("address", invitation.host); put("port", invitation.port); put("method", invitation.method); put("password", invitation.password)
                    }) }) })
                })
                add(buildJsonObject { put("tag", BLOCK); put("protocol", "blackhole") })
            })
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {
                    add(buildJsonObject { put("type", "field"); put("ip", JsonArray(PRIVATE.map(::JsonPrimitive))); put("outboundTag", BLOCK) })
                    add(buildJsonObject { put("type", "field"); put("network", "tcp,udp"); put("outboundTag", GUEST_OUT) })
                })
            })
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }
}
