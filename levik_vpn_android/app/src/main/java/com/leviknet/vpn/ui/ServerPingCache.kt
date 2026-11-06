package com.leviknet.vpn.ui

/** UI entry probes are advisory and scoped to the exact physical network and server configuration. */
internal class ServerPingCache {
    private data class Entry(val fingerprint: String, val atMs: Long, val latencyMs: Long?)
    private var network: String? = null
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun read(network: String?, fingerprints: Map<String, String>, nowMs: Long): Map<String, Long?> {
        if (network == null || network != this.network) return emptyMap()
        return fingerprints.mapNotNull { (id, fingerprint) ->
            entries[id]?.takeIf { entry ->
                entry.fingerprint == fingerprint && nowMs - entry.atMs in 0 until
                    (if (entry.latencyMs == null) FAILURE_TTL_MS else SUCCESS_TTL_MS)
            }?.let { id to it.latencyMs }
        }.toMap()
    }

    @Synchronized
    fun store(network: String?, fingerprints: Map<String, String>, values: Map<String, Long?>, nowMs: Long) {
        if (network == null) return
        if (network != this.network) { entries.clear(); this.network = network }
        entries.entries.removeAll { nowMs - it.value.atMs !in 0 until SUCCESS_TTL_MS }
        values.forEach { (id, value) -> fingerprints[id]?.let { entries[id] = Entry(it, nowMs, value) } }
        // Profiles are bounded; retaining only this batch is safe if malformed input exceeds the bound.
        if (entries.size > 256) entries.keys.retainAll(values.keys.take(256).toSet())
    }

    companion object {
        const val SUCCESS_TTL_MS = 30_000L
        const val FAILURE_TTL_MS = 10_000L
    }
}
