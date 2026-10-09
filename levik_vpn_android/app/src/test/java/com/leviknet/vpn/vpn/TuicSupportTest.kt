package com.leviknet.vpn.vpn

import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuicSupportTest {
    @Test
    fun `parses a pinned IP-literal TUIC link from a base64 subscription`() {
        val source = Base64.getEncoder().encodeToString(
            listOf(vlessLink("94.156.114.70"), tuicLink()).joinToString("\n").encodeToByteArray(),
        )

        val server = TuicLinkParser.parse(source).single()

        assertEquals(TunnelEngineKind.LEVIK_TUIC, server.engine)
        assertEquals(TunnelServerCategory.REGULAR, server.category)
        assertEquals("DE", server.countryCode)
        assertEquals("TUIC", server.name)
        val config = requireNotNull(server.tuicConfig)
        assertEquals("94.156.114.70", config.address)
        assertEquals(8443, config.port)
        assertEquals(UUID, config.uuid)
        assertEquals(PASSWORD, config.password)
        assertEquals("www.samsung.com", config.serverName)
        assertEquals(listOf("h3"), config.alpn)
        assertTrue(config.caCertificatePem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(config.caCertificatePem.endsWith("\n-----END CERTIFICATE-----\n"))
    }

    @Test
    fun `skips TUIC links without a pinned CA, with a host name or invalid values`() {
        listOf(
            tuicLink(ca = ""),
            tuicLink(ca = "AAAA"),
            tuicLink(host = "tuic.example.com"),
            tuicLink(sni = "bad name"),
            tuicLink(extra = "&congestion_control=reno"),
            "tuic://not-a-uuid:$PASSWORD@94.156.114.70:8443?sni=www.samsung.com&levik_ca=$TEST_CA",
        ).forEach { link -> assertNull(link, TuicLinkParser.parseLine(link)) }
    }

    @Test
    fun `groups protocol variants of one regular server and never mobile servers`() {
        val vless = xrayServer("v", "vless", "94.156.114.70")
        val hysteria = xrayServer("h", "hysteria", "94.156.114.70")
        val canary = xrayServer("c", "vless", "94.156.114.70")
        val tuic = requireNotNull(TuicLinkParser.parseLine(tuicLink()))
        val lte = xrayServer("l", "vless", "94.156.114.70").copy(category = TunnelServerCategory.MOBILE)

        val groups = groupProtocolVariants(listOf(vless, hysteria, canary, tuic, lte))

        assertEquals(
            listOf(listOf("v", "h", tuic.id), listOf("c"), listOf("l")),
            groups.map { group -> group.variants.map(TunnelServer::id) },
        )
        assertEquals(tuic, groups.first().active(tuic.id))
        assertEquals(vless, groups.first().active(null))
        assertEquals(listOf("VLESS", "Hysteria 2", "TUIC"), groups.first().variants.map { it.protocol().label })
    }

    @Test
    fun `routes the selected TUIC server through the loopback sidecar in the regular config`() {
        val vless = xrayServer("v", "vless", "94.156.114.70")
        val tuic = requireNotNull(TuicLinkParser.parseLine(tuicLink()))
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(vless, tuic),
        )
        val builder = XrayConfigBuilder(Json, Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC))
        val proxy = LocalProxyEndpoint("127.0.0.1", 41_000, "u".repeat(24), "p".repeat(48))

        val config = Json.parseToJsonElement(
            builder.build(builder.withTuicProxy(profile, tuic.id, proxy), tuic.id, 42),
        ).jsonObject
        val selected = config.getValue("outbounds").jsonArray.first().jsonObject

        assertEquals(tuic.tag, selected.getValue("tag").jsonPrimitive.content)
        assertEquals("socks", selected.getValue("protocol").jsonPrimitive.content)
        val settings = selected.getValue("settings").jsonObject
        assertEquals("127.0.0.1", settings.getValue("address").jsonPrimitive.content)
        assertEquals("41000", settings.getValue("port").jsonPrimitive.content)
        assertEquals("u".repeat(24), settings.getValue("user").jsonPrimitive.content)
    }

    private fun xrayServer(id: String, protocol: String, address: String) = TunnelServer(
        id = id,
        tag = "tag-$id",
        name = id,
        countryCode = "DE",
        outbound = buildJsonObject {
            put("protocol", protocol)
            put("tag", "tag-$id")
            put("settings", buildJsonObject {
                put("address", address)
                put("port", 443)
            })
        },
        category = TunnelServerCategory.REGULAR,
    )

    private fun vlessLink(host: String) = "vless://$UUID@$host:443?security=reality#${encode("🇩🇪 🚀 Prime")}"

    private fun tuicLink(
        host: String = "94.156.114.70",
        ca: String = TEST_CA,
        sni: String = "www.samsung.com",
        extra: String = "",
    ) = "tuic://$UUID:$PASSWORD@$host:8443?sni=${encode(sni)}&alpn=h3&udp_relay_mode=native&levik_ca=$ca$extra#${encode("🇩🇪 TUIC")}"

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val UUID = "123e4567-e89b-42d3-a456-426614174000"
        const val PASSWORD = "_EnbjPjIrpPjymZ63JYDPCUY7WN9ZvWv"
        // Self-signed CA used only by these tests (base64url DER).
        const val TEST_CA = "MIIBLTCB1KADAgECAgkAlrWlAi74LtUwCgYIKoZIzj0EAwIwEjEQMA4GA1UEAwwHVGVzdCBDQTAeFw0yNjEwMDkwNjMzMzlaFw0zNjEwMDYwNjMzMzlaMBIxEDAOBgNVBAMMB1Rlc3QgQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQMkvwcR00n8QEH8ejxpVyrdEu2mitTmHTzJVVb-D2knRhRrsjtu5BW_5G6nQhjImjchTZSEvYh58TTUkbLgujloxMwETAPBgNVHRMBAf8EBTADAQH_MAoGCCqGSM49BAMCA0gAMEUCIQCS7Jk2t47s5ODjcF4pvBsQRoz_9CyV_roiGAHkKEDm4wIgJ2W-0_H9Nr4eiUTxs3o6LwMmI0nbP_oM6G-6pDD3fl4"
    }
}
