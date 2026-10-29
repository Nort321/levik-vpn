package com.leviknet.vpn.core.sharing

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SharingConfigTest {
    private val json = Json
    private val invite = SharingInvitation(host = "192.168.137.1", port = 49200,
        password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
        name = "TEST PHONE", expiresAt = 1_900_000_000_000L)
    private val base = """{"log":{"loglevel":"none"},"dns":{"servers":["1.1.1.1"]},"inbounds":[{"tag":"tun-in","protocol":"tun","port":0,"settings":{"name":"levik0","mtu":1400}}],"outbounds":[{"tag":"test-vpn","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":18080}]}},{"tag":"direct","protocol":"freedom"}],"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","network":"tcp,udp","outboundTag":"direct"}]}}"""
    private fun host(): JsonObject = json.parseToJsonElement(SharingConfig.host(base, SharingHostSession(invite, 49201, "/tmp/levik-sharing-test.sock"), "test-vpn", json)).jsonObject

    @Test fun `listeners bind selected LAN address and guest transport is encrypted`() {
        val inbounds = host().getValue("inbounds").jsonArray.map { it.jsonObject }.filter { it["tag"]?.jsonPrimitive?.content in setOf(SharingConfig.HTTP_IN, SharingConfig.GUEST_IN) }
        assertEquals(2, inbounds.size)
        assertTrue(inbounds.all { it["listen"]?.jsonPrimitive?.content == invite.host })
        val encrypted = inbounds.single { it["protocol"]?.jsonPrimitive?.content == "shadowsocks" }
        assertEquals("aes-256-gcm", encrypted.getValue("settings").jsonObject.getValue("method").jsonPrimitive.content)
        assertEquals("tcp,udp", encrypted.getValue("settings").jsonObject.getValue("network").jsonPrimitive.content)
    }

    @Test fun `guest routing precedes owner direct bypass and protects private destinations`() {
        val rules = host().getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("levik-share-block", rules[1].getValue("outboundTag").jsonPrimitive.content)
        val blocked = rules[1].getValue("ip").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(blocked.containsAll(listOf("10.0.0.0/8", "127.0.0.0/8", "192.168.0.0/16", "::/0")))
        val guest = rules.single { it["network"]?.jsonPrimitive?.content == "tcp,udp" && it["inboundTag"] != null }
        assertEquals("test-vpn", guest.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("direct", rules.last().getValue("outboundTag").jsonPrimitive.content)
        assertEquals("levik-share-dns", host().getValue("dns").jsonObject.getValue("tag").jsonPrimitive.content)
    }

    @Test fun `refuses a direct or missing exit`() {
        for (tag in listOf("direct", "missing")) {
            try { SharingConfig.host(base, SharingHostSession(invite, 49201, "/tmp/levik-sharing-test.sock"), tag, json); fail("Accepted non-VPN exit") }
            catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }

    @Test fun `guest is account independent and never has direct fallback`() {
        val guest = json.parseToJsonElement(SharingConfig.guest(invite, 42, 49202, json)).jsonObject
        val outbound = guest.getValue("outbounds").jsonArray.first().jsonObject
        assertEquals("shadowsocks", outbound.getValue("protocol").jsonPrimitive.content)
        assertFalse(guest.toString().contains("freedom"))
        assertFalse(guest.toString().contains("subscription"))
        assertEquals("42", guest.getValue("env").jsonObject.getValue("xray.tun.fd").jsonPrimitive.content)
        val servers = outbound.getValue("settings").jsonObject.getValue("servers").jsonArray
        assertEquals(invite.host, servers.single().jsonObject.getValue("address").jsonPrimitive.content)
    }

    @Test fun `exports actual configs for native interoperability smoke using public fixed TEST key`() {
        val directory = File("build").apply { mkdirs() }
        File(directory, "sharing-smoke-host.json").writeText(SharingConfig.host(base, SharingHostSession(invite, 49201, "/tmp/levik-sharing-test.sock"), "test-vpn", json))
        File(directory, "sharing-smoke-guest.json").writeText(SharingConfig.guest(invite, 42, 49202, json))
    }
}
