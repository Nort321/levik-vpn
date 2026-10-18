package com.leviknet.vpn.vpn

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolHistoryTest {
    private val hour = 60 * 60_000L

    @Test
    fun `one dead server is a suspicion, a second one blocks the protocol`() {
        val once = ProtocolHistory().recordFailure("net", "vless-reality", "n1de", nowMs = 0)
        assertEquals(ProtocolVerdict.SUSPECT, once.verdict("net", "vless-reality", hour))
        val sameServer = once.recordFailure("net", "vless-reality", "n1de", nowMs = hour)
        assertEquals(ProtocolVerdict.SUSPECT, sameServer.verdict("net", "vless-reality", hour))
        val twice = once.recordFailure("net", "vless-reality", "n1nl", nowMs = hour)
        assertEquals(ProtocolVerdict.BLOCKED, twice.verdict("net", "vless-reality", 2 * hour))
    }

    @Test
    fun `a block lasts a day and is about one network only`() {
        val blocked = ProtocolHistory()
            .recordFailure("lte", "vless-reality", "n1de", nowMs = 0)
            .recordFailure("lte", "vless-reality", "n1nl", nowMs = 0)
        assertEquals(ProtocolVerdict.BLOCKED, blocked.verdict("lte", "vless-reality", 23 * hour))
        assertEquals(ProtocolVerdict.UNKNOWN, blocked.verdict("lte", "vless-reality", 25 * hour))
        assertEquals(ProtocolVerdict.UNKNOWN, blocked.verdict("wifi", "vless-reality", hour))
        assertEquals(ProtocolVerdict.UNKNOWN, blocked.verdict(null, "vless-reality", hour))
    }

    @Test
    fun `a success clears the failures and is remembered for a week`() {
        val history = ProtocolHistory()
            .recordFailure("net", "hysteria2", "n1de", nowMs = 0)
            .recordSuccess("net", "hysteria2", nowMs = hour)
        assertEquals(ProtocolVerdict.WORKS, history.verdict("net", "hysteria2", 2 * hour))
        assertEquals(ProtocolVerdict.UNKNOWN, history.verdict("net", "hysteria2", 8 * 24 * hour))
        // After a success failures count from zero again.
        val again = history.recordFailure("net", "hysteria2", "n1nl", nowMs = 3 * hour)
        assertEquals(ProtocolVerdict.SUSPECT, again.verdict("net", "hysteria2", 3 * hour))
    }

    @Test
    fun `old failures start over`() {
        val history = ProtocolHistory()
            .recordFailure("net", "tuic", "n1de", nowMs = 0)
            .recordFailure("net", "tuic", "n1nl", nowMs = 25 * hour)
        assertEquals(ProtocolVerdict.SUSPECT, history.verdict("net", "tuic", 25 * hour))
    }

    @Test
    fun `only the most recent networks are kept`() {
        var history = ProtocolHistory()
        repeat(ProtocolHistory.MAX_NETWORKS + 3) { index ->
            history = history.recordSuccess("net$index", "tuic", nowMs = index.toLong())
        }
        assertEquals(ProtocolHistory.MAX_NETWORKS, history.networks.size)
        assertEquals(ProtocolVerdict.UNKNOWN, history.verdict("net0", "tuic", 100))
        assertEquals(ProtocolVerdict.WORKS, history.verdict("net${ProtocolHistory.MAX_NETWORKS + 2}", "tuic", 100))
    }

    @Test
    fun `survives a round trip through storage`() {
        val json = Json { ignoreUnknownKeys = true }
        val history = ProtocolHistory()
            .recordFailure("net", "vless-reality", "n1de", nowMs = 5)
            .recordSuccess("net", "tuic", nowMs = 7)
        val decoded = json.decodeFromString(ProtocolHistory.serializer(), json.encodeToString(ProtocolHistory.serializer(), history))
        assertEquals(history, decoded)
    }
}
