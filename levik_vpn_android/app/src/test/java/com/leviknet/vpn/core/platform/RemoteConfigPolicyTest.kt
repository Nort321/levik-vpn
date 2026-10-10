package com.leviknet.vpn.core.platform

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigPolicyTest {
    private val id = "0b6f5f6e-6a8b-4c6e-9c43-2f8e7c1d9a10"
    private val response = Json.parseToJsonElement(
        """
        {
          "ok": true,
          "refreshAfterSeconds": 1,
          "flags": {
            "smart_protocol": {"enabled": true, "rolloutPercent": 100},
            "half": {"enabled": true, "rolloutPercent": 50},
            "off": {"enabled": false},
            "Bad-Key": {"enabled": true}
          },
          "announcements": [
            {"id": "$id", "level": "warning", "title": "Работы", "body": "Сервер в Нидерландах перезапускается",
             "linkUrl": "https://evil.example/x", "notify": true,
             "startsAt": "2026-10-10T10:00:00Z", "endsAt": "2026-10-10T12:00:00Z"},
            {"id": "not-an-id", "level": "info", "title": "x", "body": "y",
             "startsAt": "2026-10-10T10:00:00Z", "endsAt": "2026-10-10T12:00:00Z"}
          ],
          "protocols": {"preferred": ["vless-reality"], "avoid": ["hysteria2", "BAD PROTO"]}
        }
        """.trimIndent(),
    )
    private val start = java.time.Instant.parse("2026-10-10T10:00:00Z").toEpochMilli()

    @Test
    fun `parses and validates the configuration`() {
        val config = RemoteConfigPolicy.parse(response)!!
        assertEquals(5 * 60L, config.refreshAfterSeconds)
        assertEquals(setOf("smart_protocol", "half", "off"), config.flags.keys)
        assertEquals(1, config.announcements.size)
        val announcement = config.announcements.single()
        assertEquals(AnnouncementLevel.WARNING, announcement.level)
        assertNull(announcement.linkUrl)
        assertTrue(announcement.notify)
        assertEquals(listOf("hysteria2"), config.protocols?.avoid)
        assertNull(RemoteConfigPolicy.parse(Json.parseToJsonElement("""{"ok":false}""")))
    }

    @Test
    fun `announcements show only inside their window and until closed`() {
        val config = RemoteConfigPolicy.parse(response)
        assertEquals(1, RemoteConfigPolicy.visible(config, emptySet(), start + 1).size)
        assertTrue(RemoteConfigPolicy.visible(config, setOf(id), start + 1).isEmpty())
        assertTrue(RemoteConfigPolicy.visible(config, emptySet(), start - 1).isEmpty())
        assertTrue(RemoteConfigPolicy.visible(config, emptySet(), start + 2 * 60 * 60 * 1_000L).isEmpty())
    }

    @Test
    fun `flags respect switches and the local rollout bucket`() {
        val config = RemoteConfigPolicy.parse(response)
        assertTrue(RemoteConfigPolicy.flagEnabled(config, "smart_protocol", "salt"))
        assertFalse(RemoteConfigPolicy.flagEnabled(config, "off", "salt"))
        assertFalse(RemoteConfigPolicy.flagEnabled(config, "missing", "salt"))
        val enabled = (0 until 1_000).count { RemoteConfigPolicy.flagEnabled(config, "half", "salt-$it") }
        assertTrue(enabled in 400..600)
        assertEquals(
            RemoteConfigPolicy.flagEnabled(config, "half", "fixed"),
            RemoteConfigPolicy.flagEnabled(config, "half", "fixed"),
        )
    }

    @Test
    fun `advice through the tunnel keeps the earlier direct advice`() {
        val config = RemoteConfigPolicy.parse(response)!!
        val direct = RemoteConfigPolicy.merge(null, response, config, start, direct = true)
        assertEquals(start, direct.adviceAtMs)
        val tunnelled = RemoteConfigPolicy.merge(
            direct,
            response,
            config.copy(protocols = ProtocolAdvice(listOf("tuic"), emptyList())),
            start + 1_000,
            direct = false,
        )
        assertEquals(direct.advice, tunnelled.advice)
        assertEquals(start, tunnelled.adviceAtMs)
        assertNotNull(RemoteConfigPolicy.currentAdvice(tunnelled, start + 1_000))
        assertNull(RemoteConfigPolicy.currentAdvice(tunnelled, start + RemoteConfigPolicy.PROTOCOL_ADVICE_TTL_MS))
    }

    @Test
    fun `stored configuration survives a round trip`() {
        val config = RemoteConfigPolicy.parse(response)!!
        val stored = RemoteConfigPolicy.merge(null, response, config, start, direct = true)
        val decoded = RemoteConfigPolicy.decodeStored(RemoteConfigPolicy.encodeStored(stored))
        assertEquals(stored, decoded)
    }

    @Test
    fun `candidates skip avoided protocols and prefer good ones`() {
        val servers = listOf("a" to "hysteria2", "b" to "vless-reality", "c" to "tuic")
        val advice = ProtocolAdvice(preferred = listOf("vless-reality"), avoid = listOf("hysteria2"))
        assertEquals(listOf("b" to "vless-reality"), RemoteConfigPolicy.candidates(servers, advice) { it.second })
        val noPreferred = ProtocolAdvice(preferred = listOf("trojan"), avoid = listOf("hysteria2"))
        assertEquals(servers.drop(1), RemoteConfigPolicy.candidates(servers, noPreferred) { it.second })
        val allAvoided = ProtocolAdvice(preferred = emptyList(), avoid = listOf("hysteria2", "vless-reality", "tuic"))
        assertEquals(servers, RemoteConfigPolicy.candidates(servers, allAvoided) { it.second })
        assertEquals(servers, RemoteConfigPolicy.candidates(servers, null) { it.second })
    }
}
