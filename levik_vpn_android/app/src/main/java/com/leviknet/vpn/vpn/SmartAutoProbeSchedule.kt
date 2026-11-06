package com.leviknet.vpn.vpn

/** Quick confirmation of failures, then bounded backoff when the whole network is unavailable. */
internal object SmartAutoProbeSchedule {
    /** Partial provider evidence is unknown health, not a continuing all-target outage. */
    fun failedRounds(previous: Int, measurement: SmartRouteMeasurement?, dataFailed: Boolean, dataVerified: Boolean = false): Int =
        if (dataFailed || !dataVerified && measurement?.samplesMs?.isEmpty() == true) previous + 1 else 0

    fun intervalMs(failedRounds: Int, failureSuspected: Boolean, pendingImprovement: Boolean, metered: Boolean, unconfirmedRounds: Int = 0): Long = when {
        failureSuspected || failedRounds > 0 -> when {
            failedRounds < 4 -> 2_000L
            failedRounds < 8 -> 10_000L
            else -> 30_000L
        }
        // Partial replies on a new network do not qualify for the steady 90-second sleep.
        // Back off nevertheless when independent targets remain unavailable for a long time.
        unconfirmedRounds > 0 -> when {
            unconfirmedRounds < 4 -> 2_000L
            unconfirmedRounds < 8 -> 10_000L
            else -> 30_000L
        }
        pendingImprovement -> 30_000L
        metered -> 90_000L
        else -> 30_000L
    }
}
