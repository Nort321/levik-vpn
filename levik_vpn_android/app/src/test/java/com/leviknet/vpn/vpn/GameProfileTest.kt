package com.leviknet.vpn.vpn

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileTest {
    private val ordinary = server("ordinary", "198.51.100.1", 443)
    private val gameDe = server("game-de", "203.0.113.10", 8443)
    private val gameFi = server("game-fi", "203.0.113.20", 8443)
    private val token = "TEdUMQ" + "A".repeat(120)

    private fun access(
        nodes: List<GameNode>? = listOf(GameNode("de", "Germany", "DE"), GameNode("fi", "Finland", "FI")),
        only: Boolean = false,
        enabled: Boolean = true,
    ) = TunnelGameAccess(
        enabled = enabled,
        endpoints = listOf(
            GameEndpoint("203.0.113.10", 8443, "vless", "de"),
            GameEndpoint("203.0.113.20", 8443, "vless", "fi"),
        ),
        nodes = nodes,
        only = only,
        relayToken = token,
    )

    @Test
    fun `game links never stay ordinary servers and become one Game server`() {
        val split = GameProfile.split(listOf(ordinary, gameDe, gameFi), access(), includeGame = true)

        assertEquals(listOf(ordinary), split.ordinary)
        val game = split.game!!
        assertEquals(TunnelEngineKind.LEVIK_GAME, game.engine)
        val config = game.gameConfig!!
        assertEquals(listOf("de", "fi"), config.routes.map(GameRoute::nodeId))
        assertEquals(listOf(0, 1), config.routes.map(GameRoute::index))
        assertEquals(token, config.relayToken)
    }

    @Test
    fun `without Game support the game links are dropped too`() {
        val split = GameProfile.split(listOf(ordinary, gameDe, gameFi), access(), includeGame = false)

        assertEquals(listOf(ordinary), split.ordinary)
        assertNull(split.game)
    }

    @Test
    fun `a disabled Game section still keeps the game links out`() {
        val split = GameProfile.split(listOf(ordinary, gameDe), access(enabled = false), includeGame = true)

        assertEquals(listOf(ordinary), split.ordinary)
        assertNull(split.game)
    }

    @Test
    fun `Game only subscriptions have no ordinary servers`() {
        val split = GameProfile.split(listOf(ordinary, gameDe), access(only = true), includeGame = true)

        assertTrue(split.ordinary.isEmpty())
        assertTrue(split.game!!.gameConfig!!.only)
    }

    @Test
    fun `disabled and unlisted nodes get no routes`() {
        val nodes = listOf(GameNode("de", "Germany", "DE", state = "disabled"))
        val split = GameProfile.split(listOf(gameDe, gameFi), access(nodes = nodes), includeGame = true)

        // fi is not listed by the bridge, so it counts as disabled as well.
        assertNull(split.game)
        assertTrue(split.ordinary.isEmpty())
    }

    @Test
    fun `a malformed token is not handed to the relays`() {
        val split = GameProfile.split(
            listOf(gameDe),
            access().copy(relayToken = "not-a-token"),
            includeGame = true,
        )

        assertNull(split.game!!.gameConfig!!.relayToken)
    }

    @Test
    fun `the Game Xray config lets each route reach only its relay`() {
        val config = GameProfile.split(listOf(gameDe, gameFi), access(), includeGame = true).game!!.gameConfig!!
        val endpoint = GameSidecarEndpoint(
            port = 40000,
            uuid = "01234567-89ab-cdef-0123-456789abcdef",
            socksPorts = listOf(40001, 40002),
            socksPassword = "p".repeat(36),
        )
        val built = Json.parseToJsonElement(
            XrayConfigBuilder(Json, Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC))
                .buildGame(config, 42, endpoint),
        ).jsonObject

        val inbounds = built.getValue("inbounds").jsonArray.map { it.jsonObject }
        assertEquals(listOf("tun", "socks", "socks"), inbounds.map { it.getValue("protocol").jsonPrimitive.content })
        assertTrue(inbounds.drop(1).all { it.getValue("listen").jsonPrimitive.content == "127.0.0.1" })

        val outbounds = built.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertEquals("freedom", outbounds.first().getValue("protocol").jsonPrimitive.content)
        val multipath = outbounds.single { it.getValue("tag").jsonPrimitive.content == "levik-game-multipath" }
        assertEquals("vless", multipath.getValue("protocol").jsonPrimitive.content)

        val rules = built.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val probe = rules.filter { rule ->
            rule["inboundTag"]?.jsonArray?.single()?.jsonPrimitive?.content == "levik-game-probe-0"
        }
        assertEquals(listOf("levik-game-route-0", "levik-block"), probe.map { it.getValue("outboundTag").jsonPrimitive.content })
        assertEquals("37079", probe.first().getValue("port").jsonPrimitive.content)
        // Nothing but the probe inbounds ever reaches a game node link.
        assertTrue(
            rules.filter { it.getValue("outboundTag").jsonPrimitive.content.startsWith("levik-game-route-") }
                .all { it.getValue("inboundTag").jsonArray.single().jsonPrimitive.content.startsWith("levik-game-probe-") },
        )
        val tunUdp = rules.single {
            it["inboundTag"]?.jsonArray?.single()?.jsonPrimitive?.content == "levik-tun-in" &&
                it["network"]?.jsonPrimitive?.content == "udp" && "port" !in it
        }
        assertEquals("levik-game-multipath", tunUdp.getValue("outboundTag").jsonPrimitive.content)
        assertFalse(built.toString().contains(token))
    }

    @Test
    fun `with a carrier the match stays on Game and everything else takes the ordinary server`() {
        val config = GameProfile.split(listOf(gameDe), access(), includeGame = true).game!!.gameConfig!!
        val endpoint = GameSidecarEndpoint(
            port = 40000,
            uuid = "01234567-89ab-cdef-0123-456789abcdef",
            socksPorts = listOf(40001),
            socksPassword = "p".repeat(36),
        )
        val built = Json.parseToJsonElement(
            XrayConfigBuilder(Json, Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC))
                .buildGame(config, 42, endpoint, carrier = ordinary, russianDirectCidrs = listOf("5.0.0.0/8")),
        ).jsonObject

        val outbounds = built.getValue("outbounds").jsonArray.map { it.jsonObject }
        val carrier = outbounds.single { it.getValue("tag").jsonPrimitive.content == XrayConfigBuilder.GAME_CARRIER_TAG }
        assertEquals("vless", carrier.getValue("protocol").jsonPrimitive.content)

        val tunRules = built.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
            .filter { it["inboundTag"]?.jsonArray?.single()?.jsonPrimitive?.content == "levik-tun-in" }
        fun target(network: String, port: String? = null, ip: String? = null) = tunRules.first { rule ->
            (rule["network"]?.jsonPrimitive?.content ?: network) == network &&
                (rule["port"] == null || rule["port"]?.jsonPrimitive?.content == port) &&
                (rule["ip"] == null || rule["ip"]!!.jsonArray.any { it.jsonPrimitive.content == ip })
        }.getValue("outboundTag").jsonPrimitive.content
        assertEquals("levik-game-carrier", target("tcp", ip = "34.110.179.88"))
        assertEquals("levik-game-carrier", target("udp", port = "53,443,853", ip = "34.110.179.88"))
        assertEquals("levik-game-multipath", target("udp", port = "10326", ip = "34.110.179.88"))
        assertEquals("levik-direct", target("tcp", ip = "5.0.0.0/8"))
        // Russian game servers still get the two paths.
        assertEquals("levik-game-multipath", target("udp", port = "7777", ip = "5.0.0.0/8"))
        assertEquals("levik-block", target("tcp", ip = "::/0"))
    }

    private fun server(id: String, address: String, port: Int) = TunnelServer(
        id = id,
        tag = id,
        name = id,
        countryCode = "DE",
        outbound = buildJsonObject {
            put("protocol", "vless")
            put("tag", id)
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", address)
                        put("port", port)
                        put("users", buildJsonArray { add(JsonObject(mapOf())) })
                    })
                })
            })
        },
    )
}
