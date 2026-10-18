package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.ProtocolAdvice
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
    fun `under allowlists a relay waits behind a mobile server unless that one failed here`() {
        val mobile = candidate("lte", latencyMs = 60, load = null)
        val relay = candidate("relay", protocol = "relay", latencyMs = null, silenceMeansDown = false, load = null)
            .copy(preference = 1.5)
        assertEquals(listOf("lte", "relay"), SmartServerRanking.rank(listOf(relay, mobile), null).map { it.id })
        val failed = mobile.copy(verdict = ProtocolVerdict.SUSPECT, latencyMs = 400)
        assertEquals(listOf("relay", "lte"), SmartServerRanking.rank(listOf(relay, failed), null).map { it.id })
    }
}
