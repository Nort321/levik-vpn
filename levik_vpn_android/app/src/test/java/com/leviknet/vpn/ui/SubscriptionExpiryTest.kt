package com.leviknet.vpn.ui

import java.time.Instant
import com.leviknet.vpn.vpn.VpnConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionExpiryTest {
    private val now = Instant.parse("2026-07-29T12:00:00Z")

    @Test
    fun `treats equivalent ISO instants as the same expiry`() {
        assertTrue(
            equivalentSubscriptionExpiry(
                cachedValue = "2026-08-29T13:00:00.000Z",
                accountValue = "2026-08-29T13:00:00Z",
            ),
        )
    }

    @Test
    fun `keeps unlimited and expiring subscriptions distinct`() {
        assertTrue(equivalentSubscriptionExpiry(null, null))
        assertFalse(equivalentSubscriptionExpiry(null, "2026-08-29T13:00:00Z"))
        assertFalse(equivalentSubscriptionExpiry("2026-08-29T13:00:00Z", null))
    }

    @Test
    fun `does not reuse cache for malformed or different expiries`() {
        assertFalse(equivalentSubscriptionExpiry("invalid", "invalid"))
        assertFalse(
            equivalentSubscriptionExpiry(
                cachedValue = "2026-08-29T13:00:00Z",
                accountValue = "2026-08-29T13:00:01Z",
            ),
        )
    }

    @Test
    fun `offline cached profile remains usable after auth expiry`() {
        assertTrue(cachedProfileIsUsable("2026-08-29T13:00:00Z", now))
        assertTrue(cachedProfileIsUsable(null, now))
    }

    @Test
    fun `actual subscription expiry rejects offline cached profile`() {
        assertFalse(cachedProfileIsUsable("2026-07-29T12:00:00Z", now))
        assertFalse(cachedProfileIsUsable("2026-07-29T11:59:59Z", now))
        assertFalse(cachedProfileIsUsable("invalid", now))
    }

    @Test
    fun `profile refresh becomes due without invalidating the cache`() {
        assertFalse(
            profileRefreshDue(
                issuedAt = "2026-07-29T11:00:01Z",
                now = now,
            ),
        )
        assertTrue(
            profileRefreshDue(
                issuedAt = "2026-07-29T11:00:00Z",
                now = now,
            ),
        )
        assertTrue(profileRefreshDue(issuedAt = "invalid", now = now))
        assertTrue(cachedProfileIsUsable("2026-08-29T13:00:00Z", now))
    }

    @Test
    fun `authenticated entitlement removal disconnects an active tunnel`() {
        assertTrue(
            shouldDisconnectAfterProfileRemoval(
                hadCachedProfile = true,
                hasCachedProfile = false,
                vpnState = VpnConnectionState.CONNECTED,
            ),
        )
        assertFalse(
            shouldDisconnectAfterProfileRemoval(
                hadCachedProfile = true,
                hasCachedProfile = true,
                vpnState = VpnConnectionState.CONNECTED,
            ),
        )
        assertFalse(
            shouldDisconnectAfterProfileRemoval(
                hadCachedProfile = true,
                hasCachedProfile = false,
                vpnState = VpnConnectionState.DISCONNECTED,
            ),
        )
    }
}
