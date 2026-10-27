package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.LiveLoad
import com.leviknet.vpn.core.platform.LiveLoadAnswer
import com.leviknet.vpn.core.platform.LiveLoadPolicy
import com.leviknet.vpn.core.platform.RemoteConfig
import com.leviknet.vpn.core.platform.StoredRemoteConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/app-platform.md "Node load" and "Smart Auto", as the desktop clients test them. */
class SmartLoadTest {
    private fun id(digit: Char) = "n1" + digit.toString().repeat(20)

    private fun candidate(
        id: String,
        latencyMs: Long? = 50,
        load: Double? = 0.0,
        quality: Double? = null,
        full: Boolean = false,
    ) = SmartCandidate(id, "vless-reality", latencyMs, silenceMeansDown = true, load = load, verdict = ProtocolVerdict.UNKNOWN, quality = quality, full = full)

    @Test
    fun `load weighs like a queue, capped for a full node`() {
        assertEquals(1.0, SmartServerRanking.loadFactor(0.0), 1e-9)
        assertEquals(2.0, SmartServerRanking.loadFactor(0.5), 1e-9)
        assertEquals(5.0, SmartServerRanking.loadFactor(0.8), 1e-9)
        assertEquals(20.0, SmartServerRanking.loadFactor(1.0), 1e-9)
        assertEquals(1 / 0.65, SmartServerRanking.loadFactor(null), 1e-9)
    }

    @Test
    fun `a free server a little farther away beats a crowded near one`() {
        val ranked = SmartServerRanking.rank(listOf(candidate("near-busy", 30, 0.8), candidate("far-free", 90, 0.1)), advice = null)
        assertEquals("far-free", ranked.first().id)
    }

    @Test
    fun `full nodes are left out while another remains`() {
        assertEquals(
            listOf("slow"),
            SmartServerRanking.rank(listOf(candidate("full", full = true), candidate("slow", 400)), advice = null).map(RankedServer::id),
        )
        assertEquals(listOf("full"), SmartServerRanking.rank(listOf(candidate("full", full = true)), advice = null).map(RankedServer::id))
    }

    @Test
    fun `operator quality is a factor within limits`() {
        val ranked = SmartServerRanking.rank(
            listOf(candidate("slow-route", quality = 1.5), candidate("good-route", latencyMs = 60, quality = 0.8)),
            advice = null,
        )
        assertEquals("good-route", ranked.first().id)
        assertEquals(
            SmartServerRanking.score(candidate("a", quality = 1.6), null),
            SmartServerRanking.score(candidate("a", quality = 9.0), null),
            1e-9,
        )
    }

    @Test
    fun `an old reading fades into unknown`() {
        assertEquals(0.9, SmartServerRanking.agedLoad(0.9, SmartServerRanking.LOAD_TRUST_MS)!!, 1e-9)
        val halfway = SmartServerRanking.agedLoad(0.9, (SmartServerRanking.LOAD_TRUST_MS + SmartServerRanking.LOAD_FORGET_MS) / 2)!!
        assertTrue(halfway > 0.35 && halfway < 0.9)
        assertNull(SmartServerRanking.agedLoad(0.9, SmartServerRanking.LOAD_FORGET_MS))
        assertNull(SmartServerRanking.agedLoad(null, 0))
    }

    @Test
    fun `phones spread over near-equal servers but never pick a clearly worse one`() {
        val ranked = listOf(RankedServer("a", "p", 100.0), RankedServer("b", "p", 110.0), RankedServer("c", "p", 300.0))
        assertEquals("a", SmartServerRanking.spreadTop(ranked, 0.0).first().id)
        assertEquals("b", SmartServerRanking.spreadTop(ranked, 0.99).first().id)
        val picks = (0 until 50).map { SmartServerRanking.spreadTop(ranked, it / 50.0).first().id }.toSet()
        assertEquals(setOf("a", "b"), picks)
        assertEquals(listOf("a", "b", "c"), SmartServerRanking.spreadTop(ranked, 0.7).map(RankedServer::id).sorted())
    }

    @Test
    fun `the spread stays for ten minutes on one phone and matches the desktop seed`() {
        val start = 1_700_000_400_000L
        assertEquals(SmartServerRanking.spreadSeed("salt", start), SmartServerRanking.spreadSeed("salt", start + 60_000), 0.0)
        assertNotEquals(SmartServerRanking.spreadSeed("salt", start), SmartServerRanking.spreadSeed("other", start), 0.0)
        // The same number levik_vpn_macos/tests/smartLoad.test.ts gets from Node.
        assertEquals(0.7742088274098933, SmartServerRanking.spreadSeed("salt", start), 1e-12)
    }

    @Test
    fun `an idle connection leaves a node full for five minutes by fresh readings`() {
        val now = 10_000_000L
        fun due(
            fullSince: Long? = now - SmartServerRanking.OVERLOAD_CONFIRM_MS,
            loadAgeMs: Long = 30_000,
            idle: Boolean = true,
            lastMoveAt: Long? = null,
            bestScore: Double = 100.0,
        ) = SmartServerRanking.overloadMoveDue(fullSince, loadAgeMs, idle, lastMoveAt, 1_000.0, bestScore, now)
        assertTrue(due())
        assertFalse(due(idle = false))
        assertFalse(due(fullSince = now - 60_000))
        assertFalse(due(fullSince = null))
        assertFalse(due(loadAgeMs = 10 * 60_000))
        assertFalse(due(lastMoveAt = now - 60_000))
        assertFalse(due(bestScore = 700.0))
    }

    @Test
    fun `live load keeps only node ids and sane values`() {
        val parsed = LiveLoadPolicy.parse(
            Json.parseToJsonElement(
                """{"ok":true,"load":{"${id('a')}":0.4,"bad":0.1,"${id('b')}":3},"full":["${id('a')}","x"],"quality":{"${id('a')}":0.8,"${id('b')}":9}}""",
            ),
            "\"tag\"",
        )!!
        assertEquals(mapOf(id('a') to 0.4), parsed.load)
        assertEquals(setOf(id('a')), parsed.full)
        assertEquals(mapOf(id('a') to 0.8), parsed.quality)
        assertEquals("\"tag\"", parsed.etag)
        assertNull(LiveLoadPolicy.parse(Json.parseToJsonElement("""{"ok":true,"load":{},"full":[],"quality":null}"""), "bad tag")!!.quality)
        assertNull(LiveLoadPolicy.parse(Json.parseToJsonElement("""{"ok":false}"""), null))
    }

    @Test
    fun `an answer through the tunnel keeps the operator's quality`() {
        val direct = LiveLoadPolicy.merge(null, LiveLoadAnswer.Fresh(emptyMap(), emptySet(), mapOf(id('a') to 0.8), "\"x\""), 1_000, throughTunnel = false)
        val tunnel = LiveLoadPolicy.merge(direct, LiveLoadAnswer.Fresh(mapOf(id('a') to 0.2), emptySet(), null, "\"y\""), 2_000, throughTunnel = true)
        assertEquals(mapOf(id('a') to 0.8), tunnel?.quality)
        assertEquals(1_000L, tunnel?.qualityAtMs)
        val same = LiveLoadPolicy.merge(tunnel, LiveLoadAnswer.Same, 5_000, throughTunnel = true)
        assertEquals(5_000L, same?.fetchedAtMs)
        assertEquals(mapOf(id('a') to 0.2), same?.load)
        assertEquals(tunnel, LiveLoadPolicy.merge(tunnel, LiveLoadAnswer.Unsupported, 6_000, throughTunnel = false))
    }

    @Test
    fun `the newest load wins and old quality and full marks are dropped`() {
        val config = StoredRemoteConfig(
            response = JsonObject(emptyMap()),
            config = RemoteConfig(emptyMap(), emptyList(), null, refreshAfterSeconds = 900, nodeLoad = mapOf(id('c') to 0.5)),
            fetchedAtMs = 1_000,
            advice = null,
            adviceAtMs = null,
        )
        val live = LiveLoad(mapOf(id('a') to 0.3), setOf(id('b')), mapOf(id('a') to 0.9), fetchedAtMs = 2_000, qualityAtMs = 2_000, etag = null)
        val view = LiveLoadPolicy.view(live, config, 3_000)
        assertEquals(mapOf(id('a') to 0.3), view.load)
        assertEquals(1_000L, view.ageMs)
        assertEquals(setOf(id('b')), view.full)
        assertEquals(mapOf(id('a') to 0.9), view.quality)
        assertEquals(mapOf(id('a') to 0.3, id('b') to 1.0), LiveLoadPolicy.shown(view))
        // An older website: only the configuration has the load.
        assertEquals(mapOf(id('c') to 0.5), LiveLoadPolicy.view(null, config, 3_000).load)
        val late = LiveLoadPolicy.view(live, config, 2_000 + 3 * 60 * 60_000L)
        assertTrue(late.full.isEmpty() && late.quality.isEmpty())
        assertTrue(LiveLoadPolicy.shown(late).isEmpty())
    }

    @Test
    fun `ping takes the median of three and ignores a lost packet`() {
        assertEquals(42L, ServerPinger.robustLatency(listOf(40, 1_040, 42)))
        assertEquals(41L, ServerPinger.robustLatency(listOf(null, 1_040, 41)))
        assertEquals(60L, ServerPinger.robustLatency(listOf(null, null, 60)))
        assertNull(ServerPinger.robustLatency(listOf(null, null, null)))
    }
}
