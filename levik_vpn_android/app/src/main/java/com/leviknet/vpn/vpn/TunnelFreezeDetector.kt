package com.leviknet.vpn.vpn

/**
 * Notices a tunnel that sends but hardly receives, docs/app-platform.md
 * "Smart Auto": operators that throttle a protocol freeze each connection
 * after about 16 KB, so pages hang while the small health probes still pass.
 * Ordinary use downloads more than it uploads; uploads get acknowledgements.
 * A hit only asks for a traffic check, so a false alarm costs 64 KB.
 */
internal class TunnelFreezeDetector(
    private val windowMs: Long = WINDOW_MS,
    private val minUploadBytes: Long = MIN_UPLOAD_BYTES,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private data class Sample(val atMs: Long, val rx: Long, val tx: Long)

    private val samples = ArrayDeque<Sample>()
    private var lastAlarmAtMs = Long.MIN_VALUE

    /** Totals of the tunnel's own sockets; true when the last window looks frozen. */
    @Synchronized
    fun record(rxBytes: Long, txBytes: Long, nowMs: Long): Boolean {
        // Counters restart with a new connection.
        if (samples.lastOrNull()?.let { rxBytes < it.rx || txBytes < it.tx } == true) samples.clear()
        samples.addLast(Sample(nowMs, rxBytes, txBytes))
        while (samples.size > 2 && nowMs - samples[1].atMs >= windowMs) samples.removeFirst()
        val first = samples.first()
        if (nowMs - first.atMs < windowMs) return false
        val received = rxBytes - first.rx
        val sent = txBytes - first.tx
        if (sent < minUploadBytes || received * RECEIVE_RATIO >= sent) return false
        if (lastAlarmAtMs != Long.MIN_VALUE && nowMs - lastAlarmAtMs < cooldownMs) return false
        lastAlarmAtMs = nowMs
        samples.clear()
        return true
    }

    @Synchronized
    fun reset() {
        samples.clear()
        lastAlarmAtMs = Long.MIN_VALUE
    }

    private companion object {
        const val WINDOW_MS = 12_000L
        const val MIN_UPLOAD_BYTES = 24 * 1024L
        const val COOLDOWN_MS = 2 * 60_000L

        /** Frozen when less than half as much comes back as goes out. */
        const val RECEIVE_RATIO = 2L
    }
}
