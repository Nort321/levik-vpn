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
    fun `a recent failure counts until it gets old or the server works again`() {
        val failed = ProtocolHistory().recordFailure("net", "server:lte", "server:lte", nowMs = 0)
        assertEquals(true, failed.failedWithin("net", "server:lte", hour - 1, hour))
        assertEquals(false, failed.failedWithin("net", "server:lte", hour, hour))
        assertEquals(false, failed.failedWithin("other", "server:lte", 0, hour))
        val works = failed.recordSuccess("net", "server:lte", nowMs = 1)
        assertEquals(false, works.failedWithin("net", "server:lte", 2, hour))
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

    @Test
    fun `the last server that carried traffic is the network's winner until it fails`() {
        val history = ProtocolHistory()
            .recordSuccess("lte", "vless-reality", nowMs = 0, serverId = "de-reality")
            .recordSuccess("wifi", "hysteria2", nowMs = 0, serverId = "nl-hy2")
        assertEquals("de-reality", history.winner("lte"))
        assertEquals("nl-hy2", history.winner("wifi"))
        // Another server failing leaves the winner alone; the winner failing clears it.
        val other = history.recordFailure("lte", "vless-reality", "n1nl", nowMs = hour, failedServerId = "nl-reality")
        assertEquals("de-reality", other.winner("lte"))
        val failed = other.recordFailure("lte", "vless-reality", "n1de", nowMs = hour, failedServerId = "de-reality")
        assertEquals(null, failed.winner("lte"))
        assertEquals("nl-hy2", failed.winner("wifi"))
    }

    @Test
    fun `winners of forgotten networks are forgotten too`() {
        var history = ProtocolHistory().recordSuccess("net-0", "tuic", nowMs = 0, serverId = "first")
        for (index in 1..ProtocolHistory.MAX_NETWORKS) {
            history = history.recordSuccess("net-$index", "tuic", nowMs = index.toLong(), serverId = "s$index")
        }
        assertEquals(null, history.winner("net-0"))
        assertEquals("s${ProtocolHistory.MAX_NETWORKS}", history.winner("net-${ProtocolHistory.MAX_NETWORKS}"))
        assertEquals(ProtocolHistory.MAX_NETWORKS, history.winners.size)
    }
    @Test
    fun `startup winner requires recent evidence for the exact network and server configuration`() {
        val history = ProtocolHistory().recordSuccess("wifi", "tuic", 100L, "a", context = "config-a")
        assertEquals("a", history.recentWinner("wifi", mapOf("a" to "config-a"), 200L))
        assertEquals(null, history.recentWinner("lte", mapOf("a" to "config-a"), 200L))
        assertEquals(null, history.recentWinner("wifi", mapOf("a" to "config-new"), 200L))
        assertEquals(null, history.recentWinner("wifi", emptyMap(), 200L))
        assertEquals(null, history.recentWinner("wifi", mapOf("a" to "config-a"), 99L))
        assertEquals(null, history.recentWinner("wifi", mapOf("a" to "config-a"), 100L + ProtocolHistory.STARTUP_WINNER_TTL_MS))
        val failed = history.recordFailure("wifi", "tuic", "node-a", 200L, failedServerId = "a")
        assertEquals(null, failed.recentWinner("wifi", mapOf("a" to "config-a"), 300L))
    }

    @Test
    fun `legacy history and protocol only success cannot renew a stale startup winner`() {
        val legacy = ProtocolHistory(winners = mapOf("wifi" to "a"))
        assertEquals(null, legacy.recentWinner("wifi", mapOf("a" to "config-a"), 200L))
        val history = ProtocolHistory().recordSuccess("wifi", "tuic", 100L, "a", context = "config-a")
            .recordSuccess("wifi", "tuic", ProtocolHistory.STARTUP_WINNER_TTL_MS)
        assertEquals(null, history.recentWinner("wifi", mapOf("a" to "config-a"), 100L + ProtocolHistory.STARTUP_WINNER_TTL_MS))
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(ProtocolHistory.serializer(), json.encodeToString(ProtocolHistory.serializer(), history))
        assertEquals(history, decoded)
    }

    @Test
    fun `handover keeps a bounded network hint across a release build without calling it fresh proof`() {
        val history = ProtocolHistory().recordSuccess("lte", "vless-xhttp", 100L, "lte-server", context = "config|route-a")
        val contexts = mapOf("lte-server" to "config|route-a")
        val later = 100L + 30 * 60_000L
        assertEquals(null, history.recentWinner("lte", contexts, later))
        assertEquals("lte-server", history.recentWinner("lte", contexts, later, ProtocolHistory.HANDOVER_HINT_TTL_MS))
        assertEquals(null, history.recentWinner("wifi", contexts, later, ProtocolHistory.HANDOVER_HINT_TTL_MS))
        assertEquals(null, history.recentWinner("lte", mapOf("lte-server" to "config|route-b"), later, ProtocolHistory.HANDOVER_HINT_TTL_MS))
        assertEquals(null, history.recentWinner("lte", contexts, 100L + ProtocolHistory.HANDOVER_HINT_TTL_MS, Long.MAX_VALUE))
        assertEquals(null, history.forgetWinner("lte", "lte-server").recentWinner("lte", contexts, later, ProtocolHistory.HANDOVER_HINT_TTL_MS))
    }

    @Test
    fun `a failed route drops only its startup promise without inventing a protocol block`() {
        val history = ProtocolHistory().recordSuccess("wifi", "tuic", 100L, "a", context = "config-a")
            .recordSuccess("lte", "tuic", 100L, "b", context = "config-b")
        assertEquals(history, history.forgetWinner("wifi", "other"))
        val forgotten = history.forgetWinner("wifi", "a")
        assertEquals(null, forgotten.recentWinner("wifi", mapOf("a" to "config-a"), 200L))
        assertEquals("b", forgotten.recentWinner("lte", mapOf("b" to "config-b"), 200L))
        assertEquals(ProtocolVerdict.WORKS, forgotten.verdict("wifi", "tuic", 200L))
    }

}
