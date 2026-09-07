package com.leviknet.vpn.vpn

import com.leviknet.vpn.data.SplitTunnelMode
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

    @Test
    fun `vpn app cannot be excluded from its own health probes`() {
        assertEquals(
            setOf("com.example.browser"),
            splitTunnelPackagesForBuilder(
                mode = SplitTunnelMode.DISALLOWED,
                configuredPackages = setOf("com.example.browser", "com.leviknet.vpn"),
                vpnPackageName = "com.leviknet.vpn",
            ),
        )
        assertEquals(
            setOf("com.example.browser", "com.leviknet.vpn"),
            splitTunnelPackagesForBuilder(
                mode = SplitTunnelMode.ALLOWED,
                configuredPackages = setOf("com.example.browser"),
                vpnPackageName = "com.leviknet.vpn",
            ),
        )
    }

    @Test
    fun `empty allow-list preserves Android all-app routing semantics`() {
        assertTrue(
            splitTunnelPackagesForBuilder(
                mode = SplitTunnelMode.ALLOWED,
                configuredPackages = emptySet(),
                vpnPackageName = "com.leviknet.vpn",
            ).isEmpty(),
        )
    }
}
