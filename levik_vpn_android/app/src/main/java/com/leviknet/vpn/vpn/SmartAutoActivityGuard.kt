package com.leviknet.vpn.vpn

/** Only actual TUN traffic counts; private measurement inbounds cannot keep the user busy. */
internal class SmartAutoActivityGuard {
    private var previous: SmartAutoTrafficCounters? = null
    private var observedAt: Long? = null
    private var quietSince: Long? = null
    private val window = java.util.ArrayDeque<Pair<Long, Long>>()

    @Synchronized fun observe(bytes: SmartAutoTrafficCounters?, nowMs: Long) {
        if (bytes == null || bytes.uplink < 0 || bytes.downlink < 0) { previous = null; observedAt = null; quietSince = null; window.clear(); return }
        val old = previous
        val elapsed = observedAt?.let { nowMs - it }
        if (old == bytes && elapsed == 0L) return
        if (old == null || bytes.uplink < old.uplink || bytes.downlink < old.downlink || elapsed == null || elapsed <= 0 || elapsed > FRESH_MS) {
            previous = bytes; observedAt = nowMs; quietSince = null; window.clear(); return
        }
        val delta = (bytes.uplink - old.uplink).toDouble() + (bytes.downlink - old.downlink).toDouble()
        val rate = delta * 1000.0 / elapsed
        window.addLast(nowMs to delta.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong())
        while (window.isNotEmpty() && nowMs - window.first.first >= 5_000L) window.removeFirst()
        val busy = rate >= 16 * 1024 || window.sumOf { it.second } >= 64 * 1024
        if (busy || rate >= 4 * 1024) quietSince = null else if (quietSince == null) quietSince = nowMs
        previous = bytes
        observedAt = nowMs
    }

    @Synchronized fun allowed(nowMs: Long): Boolean = observedAt?.let { nowMs - it in 0..FRESH_MS } == true &&
        quietSince?.let { nowMs - it >= QUIET_MS } == true

    companion object { const val QUIET_MS = 60_000L; const val FRESH_MS = 10_000L }
}
