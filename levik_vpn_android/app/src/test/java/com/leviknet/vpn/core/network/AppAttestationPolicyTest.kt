package com.leviknet.vpn.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAttestationPolicyTest {
    @Test
    fun `play release requires integrity`() {
        assertTrue(
            AppAttestationPolicy.requiresIntegrity(
                playIntegrityEnabled = true,
                isDebugBuild = false,
            ),
        )
    }

    @Test
    fun `play debug remains non-blocking`() {
        assertFalse(
            AppAttestationPolicy.requiresIntegrity(
                playIntegrityEnabled = true,
                isDebugBuild = true,
            ),
        )
    }

    @Test
    fun `direct variants never require integrity`() {
        assertFalse(
            AppAttestationPolicy.requiresIntegrity(
                playIntegrityEnabled = false,
                isDebugBuild = false,
            ),
        )
        assertFalse(
            AppAttestationPolicy.requiresIntegrity(
                playIntegrityEnabled = false,
                isDebugBuild = true,
            ),
        )
    }
}
