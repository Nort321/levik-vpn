package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionSyncWorkerTest {
    @Test
    fun `expired account session preserves cached profile and active VPN`() {
        assertEquals(
            SubscriptionSyncFailureAction.PRESERVE_OFFLINE_PROFILE,
            subscriptionSyncFailureAction(ApiException.Unauthorized(), runAttemptCount = 0),
        )
    }

    @Test
    fun `transient sync failures retry before the attempt limit`() {
        assertEquals(
            SubscriptionSyncFailureAction.RETRY,
            subscriptionSyncFailureAction(IllegalStateException("temporary"), runAttemptCount = 2),
        )
    }

    @Test
    fun `repeated sync failures stop retrying`() {
        assertEquals(
            SubscriptionSyncFailureAction.FAIL,
            subscriptionSyncFailureAction(IllegalStateException("persistent"), runAttemptCount = 3),
        )
    }

    @Test
    fun `revoked relay capability disconnects a running or selected relay`() {
        assertTrue(
            relayCapabilityRevocationRequiresDisconnect(
                currentEngine = TunnelEngineKind.LEVIK_RELAY,
                selectedEngine = TunnelEngineKind.XRAY,
                relayCapabilityEnabled = false,
                connectionState = VpnConnectionState.CONNECTED,
            ),
        )
        assertTrue(
            relayCapabilityRevocationRequiresDisconnect(
                currentEngine = null,
                selectedEngine = TunnelEngineKind.LEVIK_RELAY,
                relayCapabilityEnabled = false,
                connectionState = VpnConnectionState.CONNECTING,
            ),
        )
    }

    @Test
    fun `relay capability retention and inactive vpn do not trigger disconnect`() {
        assertFalse(
            relayCapabilityRevocationRequiresDisconnect(
                currentEngine = TunnelEngineKind.LEVIK_RELAY,
                selectedEngine = TunnelEngineKind.LEVIK_RELAY,
                relayCapabilityEnabled = true,
                connectionState = VpnConnectionState.CONNECTED,
            ),
        )
        assertFalse(
            relayCapabilityRevocationRequiresDisconnect(
                currentEngine = null,
                selectedEngine = TunnelEngineKind.LEVIK_RELAY,
                relayCapabilityEnabled = false,
                connectionState = VpnConnectionState.DISCONNECTED,
            ),
        )
    }
}
