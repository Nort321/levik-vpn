package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.WhitelistMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileServerSwitchPolicyTest {
    @Test
    fun `switches regular server only after two active cellular confirmations`() {
        val policy = MobileServerSwitchPolicy(requiredConfirmations = 2, cooldownMs = 0)

        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(true, false, true, WhitelistMode.ACTIVE, "cell", 1),
        )
        assertEquals(
            MobileServerSwitchDecision.TO_MOBILE,
            policy.evaluate(true, false, true, WhitelistMode.ACTIVE, "cell", 2),
        )
    }

    @Test
    fun `does not enter mobile mode from wifi or unknown probes`() {
        val policy = MobileServerSwitchPolicy(requiredConfirmations = 1, cooldownMs = 0)

        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(true, false, false, WhitelistMode.ACTIVE, "wifi", 1),
        )
        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(true, false, true, WhitelistMode.UNKNOWN, "cell", 2),
        )
    }

    @Test
    fun `returns from mobile mode after two unrestricted confirmations`() {
        val policy = MobileServerSwitchPolicy(requiredConfirmations = 2, cooldownMs = 0)

        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(true, true, true, WhitelistMode.INACTIVE, "cell", 1),
        )
        assertEquals(
            MobileServerSwitchDecision.TO_REGULAR,
            policy.evaluate(true, true, true, WhitelistMode.INACTIVE, "cell", 2),
        )
    }

    @Test
    fun `a server chosen by hand also leaves for a mobile one under allowlists`() {
        val policy = MobileServerSwitchPolicy(requiredConfirmations = 1, cooldownMs = 0)

        assertEquals(
            MobileServerSwitchDecision.TO_MOBILE,
            policy.evaluate(false, false, true, WhitelistMode.ACTIVE, "cell", 1),
        )
        assertTrue(!policy.hasPendingCandidate())
    }

    @Test
    fun `by hand the way back leads only where the app moved away from`() {
        val policy = MobileServerSwitchPolicy(requiredConfirmations = 1, cooldownMs = 0)

        // A mobile server the user picked stays.
        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(false, true, true, WhitelistMode.INACTIVE, "cell", 1),
        )
        assertEquals(
            MobileServerSwitchDecision.TO_REGULAR,
            policy.evaluate(false, true, true, WhitelistMode.INACTIVE, "cell", 2, returnPending = true),
        )
    }

    @Test
    fun `a regular server the user picked under allowlists is kept`() {
        val policy = MobileServerSwitchPolicy(requiredConfirmations = 2, cooldownMs = 0)

        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(false, false, true, WhitelistMode.ACTIVE, "cell", 1, userOverride = true),
        )
        assertTrue(!policy.hasPendingCandidate())
        policy.evaluate(true, false, true, WhitelistMode.ACTIVE, "cell", 2)
        assertTrue(policy.hasPendingCandidate())
    }
}
