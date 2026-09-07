package com.leviknet.vpn.vpn

internal enum class TunnelHealthAssessment {
    HEALTHY,
    GRACE_PERIOD,
    DEGRADED,
    UNHEALTHY,
}

internal data class TunnelHealthDecision(
    val assessment: TunnelHealthAssessment,
    val consecutiveFailures: Int,
)

/**
 * Stateful protection against failover loops caused by transient carrier and probe failures.
 * Time is supplied by the caller so the policy remains deterministic and unit-testable.
 */
internal class TunnelHealthPolicy(
    private val requiredFailureRounds: Int = 3,
    private val networkGraceMs: Long = 20_000L,
    private val recoveryCooldownMs: Long = 5 * 60_000L,
    private val candidateBackoffMs: Long = 15 * 60_000L,
) {
    private var consecutiveFailures = 0
    private var graceUntilMs = Long.MIN_VALUE
    private var lastRecoveryAtMs = Long.MIN_VALUE
    private val candidateBackoffUntilMs = mutableMapOf<String, Long>()

    init {
        require(requiredFailureRounds > 0)
        require(networkGraceMs >= 0L)
        require(recoveryCooldownMs >= 0L)
        require(candidateBackoffMs >= 0L)
    }

    @Synchronized
    fun onTunnelStarted(nowMs: Long) {
        consecutiveFailures = 0
        graceUntilMs = saturatedAdd(nowMs, networkGraceMs)
    }

    @Synchronized
    fun onUnderlyingNetworkChanged(nowMs: Long) {
        consecutiveFailures = 0
        graceUntilMs = saturatedAdd(nowMs, networkGraceMs)
        candidateBackoffUntilMs.clear()
    }

    @Synchronized
    fun evaluateProbe(success: Boolean, nowMs: Long): TunnelHealthDecision {
        if (success) {
            consecutiveFailures = 0
            return TunnelHealthDecision(TunnelHealthAssessment.HEALTHY, 0)
        }
        if (nowMs < graceUntilMs) {
            consecutiveFailures = 0
            return TunnelHealthDecision(TunnelHealthAssessment.GRACE_PERIOD, 0)
        }

        consecutiveFailures += 1
        val reportedFailures = consecutiveFailures
        val assessment = if (consecutiveFailures >= requiredFailureRounds) {
            consecutiveFailures = 0
            TunnelHealthAssessment.UNHEALTHY
        } else {
            TunnelHealthAssessment.DEGRADED
        }
        return TunnelHealthDecision(assessment, reportedFailures)
    }

    @Synchronized
    fun canStartRecovery(nowMs: Long): Boolean =
        lastRecoveryAtMs == Long.MIN_VALUE || nowMs - lastRecoveryAtMs >= recoveryCooldownMs

    @Synchronized
    fun recordRecovery(nowMs: Long) {
        lastRecoveryAtMs = nowMs
        consecutiveFailures = 0
    }

    @Synchronized
    fun markCandidateFailed(serverId: String, nowMs: Long) {
        candidateBackoffUntilMs[serverId] = saturatedAdd(nowMs, candidateBackoffMs)
    }

    @Synchronized
    fun markCandidateHealthy(serverId: String) {
        candidateBackoffUntilMs.remove(serverId)
    }

    @Synchronized
    fun isCandidateEligible(serverId: String, nowMs: Long): Boolean {
        candidateBackoffUntilMs.entries.removeAll { it.value <= nowMs }
        return candidateBackoffUntilMs[serverId]?.let { nowMs >= it } ?: true
    }

    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        graceUntilMs = Long.MIN_VALUE
        lastRecoveryAtMs = Long.MIN_VALUE
        candidateBackoffUntilMs.clear()
    }

    private fun saturatedAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}
