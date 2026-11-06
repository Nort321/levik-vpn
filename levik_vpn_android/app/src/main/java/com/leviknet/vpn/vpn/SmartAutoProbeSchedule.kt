package com.leviknet.vpn.vpn

/** Quick confirmation of failures, then bounded backoff when the whole network is unavailable. */
internal object SmartAutoProbeSchedule {
    /** Partial provider evidence is unknown health, not a continuing all-target outage. */
    fun failedRounds(previous: Int, measurement: SmartRouteMeasurement?, dataFailed: Boolean): Int =
        if (dataFailed || measurement?.samplesMs?.isEmpty() == true) previous + 1 else 0

    fun intervalMs(failedRounds: Int, failureSuspected: Boolean, pendingImprovement: Boolean, metered: Boolean): Long = when {
        failureSuspected || failedRounds > 0 -> when {
            failedRounds < 4 -> 2_000L
            failedRounds < 8 -> 10_000L
            else -> 30_000L
        }
        pendingImprovement -> 30_000L
        metered -> 90_000L
        else -> 30_000L
    }
}
