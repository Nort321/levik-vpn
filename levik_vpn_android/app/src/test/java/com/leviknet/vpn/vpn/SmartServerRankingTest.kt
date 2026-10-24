package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.ProtocolAdvice
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartServerRankingTest {
    private fun candidate(
        id: String,
        protocol: String = "vless-reality",
        latencyMs: Long? = 50,
        silenceMeansDown: Boolean = true,
        load: Double? = 0.2,
        verdict: ProtocolVerdict = ProtocolVerdict.UNKNOWN,
    ) = SmartCandidate(id, protocol, latencyMs, silenceMeansDown, load, verdict)

    @Test
    fun `a free server beats a slightly faster busy one`() {
        val ranked = SmartServerRanking.rank(
            listOf(
                candidate("busy", latencyMs = 40, load = 0.95),
                candidate("free", latencyMs = 70, load = 0.1),
            ),
            advice = null,
        )
        assertEquals(listOf("free", "busy"), ranked.map(RankedServer::id))
    }

    @Test
    fun `a protocol blocked on this network is left out`() {
        val ranked = SmartServerRanking.rank(
            listOf(
                candidate("vless", latencyMs = 20, verdict = ProtocolVerdict.BLOCKED),
                candidate("hy2", protocol = "hysteria2", latencyMs = null, silenceMeansDown = false),
            ),
            advice = null,
        )
        assertEquals(listOf("hy2"), ranked.map(RankedServer::id))
    }

    @Test
    fun `everything blocked still leaves something to try`() {
        val ranked = SmartServerRanking.rank(
            listOf(candidate("a", verdict = ProtocolVerdict.BLOCKED)),
            advice = null,
        )
        assertEquals(listOf("a"), ranked.map(RankedServer::id))
    }

    @Test
    fun `a silent TCP server is down while a silent UDP server may work`() {
        val ranked = SmartServerRanking.rank(
            listOf(
                candidate("tcp-down", latencyMs = null, silenceMeansDown = true),
                candidate("udp", protocol = "tuic", latencyMs = null, silenceMeansDown = false),
            ),
            advice = null,
        )
        assertEquals(listOf("udp"), ranked.map(RankedServer::id))
    }

    @Test
    fun `own experience outweighs the operator advice`() {
        val advice = ProtocolAdvice(preferred = listOf("hysteria2"), avoid = listOf("vless-reality"))
        val ranked = SmartServerRanking.rank(
            listOf(
                candidate("vless", latencyMs = 60, verdict = ProtocolVerdict.WORKS),
                candidate("hy2", protocol = "hysteria2", latencyMs = 60),
            ),
            advice,
        )
        assertEquals("vless", ranked.first().id)
        val withoutHistory = SmartServerRanking.rank(
            listOf(candidate("vless", latencyMs = 60), candidate("hy2", protocol = "hysteria2", latencyMs = 60)),
            advice,
        )
        assertEquals("hy2", withoutHistory.first().id)
    }

    @Test
    fun `a suspect protocol goes after an unknown one of similar speed`() {
        val ranked = SmartServerRanking.rank(
            listOf(
                candidate("suspect", latencyMs = 40, verdict = ProtocolVerdict.SUSPECT),
                candidate("unknown", protocol = "vless-xhttp", latencyMs = 60),
            ),
            advice = null,
        )
        assertEquals("unknown", ranked.first().id)
    }

    @Test
    fun `the ladder moves to another protocol before another server of the same one`() {
        val ranked = listOf(
            RankedServer("de-vless", "vless-reality", 10.0),
            RankedServer("nl-vless", "vless-reality", 20.0),
            RankedServer("de-hy2", "hysteria2", 30.0),
        )
        val next = SmartServerRanking.nextRung(ranked, setOf("vless-reality"), setOf("de-vless"))
        assertEquals("de-hy2", next?.id)
        val sameProtocolLeft = SmartServerRanking.nextRung(ranked, setOf("vless-reality", "hysteria2"), setOf("de-vless", "de-hy2"))
        assertEquals("nl-vless", sameProtocolLeft?.id)
        assertNull(SmartServerRanking.nextRung(ranked, emptySet(), ranked.map(RankedServer::id).toSet()))
    }

    @Test
    fun `a running choice moves only for a clearly better server`() {
        assertTrue(SmartServerRanking.worthSwitching(null, 100.0))
        assertFalse(SmartServerRanking.worthSwitching(100.0, 90.0))
        assertTrue(SmartServerRanking.worthSwitching(100.0, 70.0))
    }

    @Test
    fun `under allowlists a relay waits behind every ordinary mobile server, however it scores`() {
        // A busy, once-failed mobile server scores far worse than a relay that worked here.
        val busy = AllowlistCandidate(
            candidate("lte-busy", protocol = "vless-xhttp", latencyMs = 300, load = 0.95, verdict = ProtocolVerdict.SUSPECT),
            relay = false,
            failedRecently = false,
        )
        val wdtt = AllowlistCandidate(
            candidate("wdtt", protocol = "relay", latencyMs = null, silenceMeansDown = false, load = null, verdict = ProtocolVerdict.WORKS),
            relay = true,
            failedRecently = false,
        )
        assertEquals(listOf("lte-busy", "wdtt"), SmartServerRanking.rankAllowlist(listOf(wdtt, busy)).map { it.id })
    }

    @Test
    fun `under allowlists a silent mobile server is tried after the answering ones, before relays`() {
        val silent = AllowlistCandidate(candidate("lte-silent", latencyMs = null), relay = false, failedRecently = false)
        val slow = AllowlistCandidate(candidate("lte-slow", latencyMs = 900, load = 0.9), relay = false, failedRecently = false)
        val relay = AllowlistCandidate(
            candidate("wdtt", protocol = "relay", latencyMs = null, silenceMeansDown = false, load = null),
            relay = true,
            failedRecently = false,
        )
        assertEquals(
            listOf("lte-slow", "lte-silent", "wdtt"),
            SmartServerRanking.rankAllowlist(listOf(relay, silent, slow)).map { it.id },
        )
    }

    @Test
    fun `under allowlists a mobile server that just failed here goes after the relays`() {
        val failed = AllowlistCandidate(candidate("lte", latencyMs = 40), relay = false, failedRecently = true)
        val other = AllowlistCandidate(candidate("lte-2", latencyMs = 120), relay = false, failedRecently = false)
        val relay = AllowlistCandidate(
            candidate("wdtt", protocol = "relay", latencyMs = null, silenceMeansDown = false, load = null),
            relay = true,
            failedRecently = false,
        )
        assertEquals(
            listOf("lte-2", "wdtt", "lte"),
            SmartServerRanking.rankAllowlist(listOf(failed, relay, other)).map { it.id },
        )
    }

    @Test
    fun `under allowlists a signed-in relay beats a far ordinary mobile server`() {
        // Beeline in Kostroma: the Polish LTE answers in a second, the VK relay worked here before.
        val poland = AllowlistCandidate(candidate("lte-pl", protocol = "vless-xhttp", latencyMs = 1000), relay = false, failedRecently = false)
        val wdtt = candidate("wdtt", protocol = "relay", latencyMs = null, silenceMeansDown = false, load = null, verdict = ProtocolVerdict.WORKS)
        assertEquals(
            listOf("wdtt", "lte-pl"),
            SmartServerRanking.rankAllowlist(
                listOf(poland, AllowlistCandidate(wdtt, relay = true, failedRecently = false, signedIn = true)),
            ).map { it.id },
        )
        // Without a login the sign-in page would scare the user: ordinary first.
        assertEquals(
            listOf("lte-pl", "wdtt"),
            SmartServerRanking.rankAllowlist(
                listOf(poland, AllowlistCandidate(wdtt, relay = true, failedRecently = false, signedIn = false)),
            ).map { it.id },
        )
    }

    @Test
    fun `under allowlists a signed-in relay goes before an ordinary mobile server however fast its CDN answers`() {
        // Beeline in Kostroma: the Polish LTE's CDN edge answers fast, the tunnel behind it takes a second.
        val poland = AllowlistCandidate(
            candidate("lte-pl", protocol = "vless-xhttp", latencyMs = 40, verdict = ProtocolVerdict.WORKS),
            relay = false,
            failedRecently = false,
            lastWinner = true,
        )
        val yandex = AllowlistCandidate(
            candidate("yandex", protocol = "yandex", latencyMs = null, silenceMeansDown = false, load = null, verdict = ProtocolVerdict.WORKS),
            relay = true,
            failedRecently = false,
            signedIn = true,
        )
        val wdtt = AllowlistCandidate(
            candidate("wdtt", protocol = "relay", latencyMs = null, silenceMeansDown = false, load = null),
            relay = true,
            failedRecently = false,
        )
        assertEquals(
            listOf("yandex", "lte-pl", "wdtt"),
            SmartServerRanking.rankAllowlist(listOf(wdtt, poland, yandex)).map { it.id },
        )
    }

    @Test
    fun `under allowlists the best of the signed-in relays goes first, the last winner here before the rest`() {
        fun relay(id: String, verdict: ProtocolVerdict, lastWinner: Boolean = false) = AllowlistCandidate(
            candidate(id, protocol = id, latencyMs = null, silenceMeansDown = false, load = null, verdict = verdict),
            relay = true,
            failedRecently = false,
            signedIn = true,
            lastWinner = lastWinner,
        )
        val lte = AllowlistCandidate(candidate("lte", latencyMs = 40), relay = false, failedRecently = false)
        assertEquals(
            listOf("wdtt", "yandex", "lte"),
            SmartServerRanking.rankAllowlist(
                listOf(lte, relay("yandex", ProtocolVerdict.UNKNOWN), relay("wdtt", ProtocolVerdict.WORKS)),
            ).map { it.id },
        )
        assertEquals(
            listOf("yandex", "wdtt", "lte"),
            SmartServerRanking.rankAllowlist(
                listOf(lte, relay("yandex", ProtocolVerdict.UNKNOWN, lastWinner = true), relay("wdtt", ProtocolVerdict.WORKS)),
            ).map { it.id },
        )
    }

    @Test
    fun `under allowlists the ordinary mobile server that last won here goes before a faster one`() {
        val winner = AllowlistCandidate(candidate("lte-ru", latencyMs = 120), relay = false, failedRecently = false, lastWinner = true)
        val faster = AllowlistCandidate(candidate("lte-pl", latencyMs = 40), relay = false, failedRecently = false)
        assertEquals(listOf("lte-ru", "lte-pl"), SmartServerRanking.rankAllowlist(listOf(faster, winner)).map { it.id })
    }

    @Test
    fun `under allowlists a signed-in relay that just failed here goes after the other relays`() {
        val failed = AllowlistCandidate(
            candidate("wdtt", protocol = "relay", latencyMs = null, silenceMeansDown = false, load = null),
            relay = true,
            failedRecently = true,
            signedIn = true,
        )
        val yandex = AllowlistCandidate(
            candidate("yandex", protocol = "yandex", latencyMs = null, silenceMeansDown = false, load = null),
            relay = true,
            failedRecently = false,
        )
        val lte = AllowlistCandidate(candidate("lte", latencyMs = 900), relay = false, failedRecently = false)
        assertEquals(
            listOf("lte", "yandex", "wdtt"),
            SmartServerRanking.rankAllowlist(listOf(failed, yandex, lte)).map { it.id },
        )
    }

    @Test
    fun `a relay counts as signed in only with the user's own VK login or Yandex sign-in`() {
        val now = Instant.parse("2026-10-24T06:00:00Z")
        fun server(id: String, engine: TunnelEngineKind, category: TunnelServerCategory, yandex: YandexServerConfig? = null) =
            TunnelServer(id, id, id, "RU", JsonObject(emptyMap()), engine = engine, category = category, yandexConfig = yandex)
        val wdtt = server("wdtt", TunnelEngineKind.LEVIK_RELAY, TunnelServerCategory.MOBILE_ALLOWLIST)
        assertTrue(wdtt.isSignedInRelay(vkSession = true, now = now))
        assertFalse(wdtt.isSignedInRelay(vkSession = false, now = now))

        fun telemost(expiresAt: Long) = YandexServerConfig(
            telemost = TelemostBootstrap(1, "a".repeat(64), "https://telemost.yandex.ru/j/12345678901234", "A".repeat(43), "b".repeat(64), expiresAt),
        )
        val signedIn = server("yandex", TunnelEngineKind.LEVIK_YANDEX, TunnelServerCategory.MOBILE_ALLOWLIST, telemost(now.epochSecond + 900))
        val expired = signedIn.copy(yandexConfig = telemost(now.epochSecond + 60))
        assertTrue(signedIn.isSignedInRelay(vkSession = false, now = now))
        assertFalse(expired.isSignedInRelay(vkSession = true, now = now))

        assertFalse(server("lte", TunnelEngineKind.XRAY, TunnelServerCategory.MOBILE).isSignedInRelay(vkSession = true, now = now))
    }
}
