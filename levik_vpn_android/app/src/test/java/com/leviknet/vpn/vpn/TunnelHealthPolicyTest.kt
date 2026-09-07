package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthPolicyTest {
    @Test
    fun `requires three failed rounds before declaring tunnel unhealthy`() {
        val policy = TunnelHealthPolicy(networkGraceMs = 0L, recoveryCooldownMs = 0L)
        policy.onTunnelStarted(0L)

        assertEquals(
            TunnelHealthAssessment.DEGRADED,
            policy.evaluateProbe(success = false, nowMs = 1L).assessment,
        )
        assertEquals(
            TunnelHealthAssessment.DEGRADED,
            policy.evaluateProbe(success = false, nowMs = 2L).assessment,
        )
        assertEquals(
            TunnelHealthAssessment.UNHEALTHY,
            policy.evaluateProbe(success = false, nowMs = 3L).assessment,
        )
        assertEquals(
            1,
            policy.evaluateProbe(success = false, nowMs = 4L).consecutiveFailures,
        )
    }

    @Test
    fun `successful probe resets accumulated failures`() {
        val policy = TunnelHealthPolicy(networkGraceMs = 0L, recoveryCooldownMs = 0L)
        policy.onTunnelStarted(0L)

        policy.evaluateProbe(success = false, nowMs = 1L)
        policy.evaluateProbe(success = false, nowMs = 2L)
        assertEquals(
            TunnelHealthAssessment.HEALTHY,
            policy.evaluateProbe(success = true, nowMs = 3L).assessment,
        )
        assertEquals(
            1,
            policy.evaluateProbe(success = false, nowMs = 4L).consecutiveFailures,
        )
    }

    @Test
    fun `ignores probe failure during network grace period`() {
        val policy = TunnelHealthPolicy(networkGraceMs = 20_000L, recoveryCooldownMs = 0L)
        policy.onUnderlyingNetworkChanged(10_000L)

        assertEquals(
            TunnelHealthAssessment.GRACE_PERIOD,
            policy.evaluateProbe(success = false, nowMs = 29_999L).assessment,
        )
        assertEquals(
            1,
            policy.evaluateProbe(success = false, nowMs = 30_000L).consecutiveFailures,
        )
    }

    @Test
    fun `recovery cooldown prevents reconnect loops`() {
        val policy = TunnelHealthPolicy(
            networkGraceMs = 0L,
            recoveryCooldownMs = 300_000L,
        )

        assertTrue(policy.canStartRecovery(1_000L))
        policy.recordRecovery(1_000L)
        assertFalse(policy.canStartRecovery(300_999L))
        assertTrue(policy.canStartRecovery(301_000L))
    }

    @Test
    fun `failed candidate remains excluded until backoff expires`() {
        val policy = TunnelHealthPolicy(candidateBackoffMs = 900_000L)

        policy.markCandidateFailed("server-b", 1_000L)
        assertFalse(policy.isCandidateEligible("server-b", 900_999L))
        assertTrue(policy.isCandidateEligible("server-b", 901_000L))
        assertTrue(policy.isCandidateEligible("server-c", 1_001L))
    }

    @Test
    fun `network change clears provider-specific candidate backoff`() {
        val policy = TunnelHealthPolicy(candidateBackoffMs = 900_000L)

        policy.markCandidateFailed("server-b", 1_000L)
        policy.onUnderlyingNetworkChanged(2_000L)

        assertTrue(policy.isCandidateEligible("server-b", 2_001L))
    }
}
