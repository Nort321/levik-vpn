package com.leviknet.vpn.vpn

import com.leviknet.vpn.data.RoutingPreset
import com.leviknet.vpn.data.SplitTunnelMode
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TunnelConnectionPolicyTest {
    @Test
    fun `all server categories honor the selected routing preset`() {
        for (category in TunnelServerCategory.entries) {
            val server = testServer(category)
            for (preset in RoutingPreset.entries) {
                val expected = if (category != TunnelServerCategory.REGULAR && preset == RoutingPreset.BYPASS_RU) {
                    EffectiveRoutingProfile.LTE
                } else {
                    EffectiveRoutingProfile.USER_SELECTED
                }
                assertEquals(expected, server.effectiveRoutingProfile(preset))
            }
        }
    }

    @Test
    fun `per-app policy invokes Android allow or deny callbacks independently of destination routing`() {
        val selectedApps = setOf("ru.tander.magnit", "com.magnit.delivery.courier", "ru.magnit.magnit_job")
        for (mode in SplitTunnelMode.entries) {
            val allowed = mutableSetOf<String>()
            val disallowed = mutableSetOf<String>()
            applySplitTunnelApplications(
                mode, selectedApps, "com.leviknet.vpn",
                addAllowed = { allowed.add(it) },
                addDisallowed = { disallowed.add(it) },
            )
            assertEquals(
                if (mode == SplitTunnelMode.ALLOWED) selectedApps + "com.leviknet.vpn" else emptySet(),
                allowed,
            )
            assertEquals(if (mode == SplitTunnelMode.DISALLOWED) selectedApps else emptySet(), disallowed)
        }
    }

    @Test
    fun `failure to apply per-app policy aborts setup instead of silently routing everything`() {
        assertThrows(IllegalStateException::class.java) {
            applySplitTunnelApplications(
                SplitTunnelMode.DISALLOWED, setOf("com.magnit.delivery.courier"), "com.leviknet.vpn",
                addAllowed = { error("Unexpected allow-list") },
                addDisallowed = { error("Android rejected application policy") },
            )
        }
    }

    private fun testServer(category: TunnelServerCategory) = TunnelServer(
        id = "test", tag = "test", name = "test", countryCode = "DE",
        outbound = buildJsonObject {}, category = category,
    )

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
