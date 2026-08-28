package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.ApiException
import org.junit.Assert.assertEquals
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
}
