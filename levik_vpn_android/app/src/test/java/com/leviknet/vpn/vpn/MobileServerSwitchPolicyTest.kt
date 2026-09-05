package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.WhitelistMode
import org.junit.Assert.assertEquals
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
    fun `manual server mode never switches`() {
        val policy = MobileServerSwitchPolicy(requiredConfirmations = 1, cooldownMs = 0)

        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(false, false, true, WhitelistMode.ACTIVE, "cell", 1),
        )
        assertEquals(
            MobileServerSwitchDecision.NONE,
            policy.evaluate(false, true, true, WhitelistMode.INACTIVE, "cell", 2),
        )
    }
}
