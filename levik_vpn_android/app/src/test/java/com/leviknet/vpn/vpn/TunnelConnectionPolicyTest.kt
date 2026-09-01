package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConnectionPolicyTest {
    @Test
    fun `allow-list optimized server requires only cellular transport`() {
        assertEquals(
            TunnelNetworkRequirementViolation.CELLULAR_NETWORK_REQUIRED,
            tunnelNetworkRequirementViolation(
                TunnelNetworkRequirement.CELLULAR_ALLOWLIST,
                isCellularNetwork = false,
            ),
        )
        assertNull(
            tunnelNetworkRequirementViolation(
                TunnelNetworkRequirement.CELLULAR_ALLOWLIST,
                isCellularNetwork = true,
            ),
        )
    }

    @Test
    fun `unrestricted engine ignores transport and allow-list detection`() {
        assertNull(
            tunnelNetworkRequirementViolation(
                TunnelNetworkRequirement.ANY,
                isCellularNetwork = false,
            ),
        )
        assertFalse(requiresDedicatedCellularRequest(TunnelNetworkRequirement.ANY))
        assertTrue(
            requiresDedicatedCellularRequest(TunnelNetworkRequirement.CELLULAR_ALLOWLIST),
        )
    }
}
