package com.leviknet.vpn.data

import com.leviknet.vpn.core.network.DeviceSummary
import com.leviknet.vpn.core.network.SubscriptionActions
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.core.network.TrafficSummary
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionOwnershipTest {
    private val now = Instant.parse("2026-07-29T12:00:00Z")

    @Test
    fun `cached profile remains only for the same active subscription`() {
        val subscriptions = listOf(
            subscription(
                uuid = "subscription-new",
                expireAt = "2026-08-29T12:00:00Z",
            ),
        )

        assertTrue(
            subscriptions.containsActiveSubscription(
                subscriptionId = "subscription-new",
                now = now,
            ),
        )
        assertFalse(
            subscriptions.containsActiveSubscription(
                subscriptionId = "subscription-old",
                now = now,
            ),
        )
    }

    @Test
    fun `expired or inactive subscription does not own cached profile`() {
        assertFalse(
            listOf(
                subscription(
                    uuid = "subscription-123",
                    expireAt = "2026-07-29T12:00:00Z",
                ),
            ).containsActiveSubscription("subscription-123", now),
        )
        assertFalse(
            listOf(
                subscription(
                    uuid = "subscription-123",
                    expireAt = "2026-08-29T12:00:00Z",
                    status = "disabled",
                ),
            ).containsActiveSubscription("subscription-123", now),
        )
    }

    private fun subscription(
        uuid: String,
        expireAt: String,
        status: String = "active",
    ) = SubscriptionSummary(
        uuid = uuid,
        tariffId = "standard",
        title = "Standard",
        status = status,
        expireAt = expireAt,
        traffic = TrafficSummary(usedBytes = 0, limitBytes = 0),
        devices = DeviceSummary(used = 1, limit = 1, items = emptyList()),
        actions = SubscriptionActions(
            renew = true,
            rotateKey = false,
            revokeDevice = true,
            slotAddon = false,
            trafficAddon = false,
        ),
    )
}
