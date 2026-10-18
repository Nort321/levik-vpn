package com.leviknet.vpn.vpn

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the traffic check said lately about a protocol on one network. */
enum class ProtocolVerdict {
    /** Failed on two different servers and has not worked since: left out. */
    BLOCKED,

    /** Failed once: tried after the others. */
    SUSPECT,

    /** Carried traffic here recently: tried first. */
    WORKS,
    UNKNOWN,
}

@Serializable
data class ProtocolRecord(
    /** Node ids where the check failed since the last success. */
    val failedNodes: Set<String> = emptySet(),
    val lastFailureAtMs: Long = 0L,
    val lastSuccessAtMs: Long = 0L,
)

/**
 * The phone's own record of which protocols carry traffic on which network,
 * docs/app-platform.md "Smart Auto". Kept on the device only; a network is
 * known by a salted hash, never by its name or addresses.
 */
@Serializable
data class ProtocolHistory(
    /** Network key → protocol → record, the most recently updated network last. */
    val networks: Map<String, Map<String, ProtocolRecord>> = emptyMap(),
    /** Network key → the server that last carried traffic there. */
    val winners: Map<String, String> = emptyMap(),
) {
    fun winner(networkKey: String): String? = winners[networkKey]

    fun verdict(networkKey: String?, protocol: String, nowMs: Long): ProtocolVerdict {
        val record = networkKey?.let { networks[it]?.get(protocol) } ?: return ProtocolVerdict.UNKNOWN
        val sinceFailure = nowMs - record.lastFailureAtMs
        return when {
            record.failedNodes.size >= BLOCK_AFTER_NODES && sinceFailure < BLOCK_TTL_MS -> ProtocolVerdict.BLOCKED
            record.failedNodes.isNotEmpty() && sinceFailure < SUSPECT_TTL_MS -> ProtocolVerdict.SUSPECT
            record.lastSuccessAtMs > 0L && nowMs - record.lastSuccessAtMs < WORKS_TTL_MS -> ProtocolVerdict.WORKS
            else -> ProtocolVerdict.UNKNOWN
        }
    }

    fun recordSuccess(networkKey: String, protocol: String, nowMs: Long, serverId: String? = null): ProtocolHistory {
        val next = update(networkKey, protocol) { ProtocolRecord(lastSuccessAtMs = nowMs, lastFailureAtMs = it.lastFailureAtMs) }
        if (serverId == null) return next
        return next.copy(winners = next.winners + (networkKey to serverId))
    }

    /**
     * A failure counts once per server: a dead server is not a blocked protocol,
     * the same protocol failing on a second server is. Failures older than a
     * day start over.
     */
    fun recordFailure(
        networkKey: String,
        protocol: String,
        nodeId: String,
        nowMs: Long,
        failedServerId: String? = null,
    ): ProtocolHistory =
        update(networkKey, protocol) { record ->
            val fresh = nowMs - record.lastFailureAtMs < BLOCK_TTL_MS
            val nodes = if (fresh) record.failedNodes else emptySet()
            record.copy(failedNodes = (nodes + nodeId).toSortedSet().take(MAX_FAILED_NODES).toSet(), lastFailureAtMs = nowMs)
        }.let { next ->
            // A protocol that stopped working here no longer names the network's winner.
            if (failedServerId != null && next.winners[networkKey] == failedServerId) {
                next.copy(winners = next.winners - networkKey)
            } else {
                next
            }
        }

    private fun update(networkKey: String, protocol: String, change: (ProtocolRecord) -> ProtocolRecord): ProtocolHistory {
        val records = networks[networkKey].orEmpty()
        val next = records + (protocol to change(records[protocol] ?: ProtocolRecord()))
        // Re-inserting moves the network to the end; the oldest ones are forgotten.
        val all = (networks - networkKey) + (networkKey to next.entries.take(MAX_PROTOCOLS).associate { it.toPair() })
        val kept = all.entries.toList().takeLast(MAX_NETWORKS).associate { it.toPair() }
        return ProtocolHistory(kept, winners.filterKeys(kept::containsKey))
    }

    companion object {
        const val BLOCK_AFTER_NODES = 2
        const val BLOCK_TTL_MS = 24 * 60 * 60_000L
        const val SUSPECT_TTL_MS = 6 * 60 * 60_000L
        const val WORKS_TTL_MS = 7 * 24 * 60 * 60_000L
        const val MAX_NETWORKS = 16
        const val MAX_PROTOCOLS = 16
        const val MAX_FAILED_NODES = 8
    }
}

/** [ProtocolHistory] in the app's private preferences; the app and the VPN service share one process. */
class ProtocolHistoryStore(context: Context, private val json: Json) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var cached: ProtocolHistory? = null

    @Synchronized
    fun current(): ProtocolHistory = cached ?: load().also { cached = it }

    @Synchronized
    fun update(change: (ProtocolHistory) -> ProtocolHistory) {
        val next = change(current())
        cached = next
        preferences.edit { putString(HISTORY, json.encodeToString(ProtocolHistory.serializer(), next)) }
    }

    private fun load(): ProtocolHistory = preferences.getString(HISTORY, null)
        ?.let { raw -> runCatching { json.decodeFromString(ProtocolHistory.serializer(), raw) }.getOrNull() }
        ?: ProtocolHistory()

    private companion object {
        const val PREFERENCES_NAME = "levik_smart_auto"
        const val HISTORY = "protocol_history"
    }
}
