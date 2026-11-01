package com.leviknet.vpn.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialPurchaseModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `older servers keep ordinary renewal behavior`() {
        val trial = json.decodeFromString<TrialSummary>("""{"eligible":false,"status":"completed"}""")
        val actions = json.decodeFromString<SubscriptionActions>(
            """{"renew":true,"rotateKey":true,"revokeDevice":true,"slotAddon":true,"trafficAddon":false}""",
        )
        assertFalse(trial.purchasePending)
        assertFalse(actions.chooseTariff)
        assertTrue(actions.renew)
    }

    @Test
    fun `expired trial can offer plan choice without enabling ordinary renewal`() {
        val trial = json.decodeFromString<TrialSummary>(
            """{"eligible":false,"status":"completed","expiresAt":"2026-10-01T00:00:00Z","purchasePending":true}""",
        )
        val actions = json.decodeFromString<SubscriptionActions>(
            """{"renew":false,"chooseTariff":true,"rotateKey":false,"revokeDevice":false,"slotAddon":false,"trafficAddon":false}""",
        )
        assertTrue(trial.purchasePending)
        assertTrue(actions.chooseTariff)
        assertFalse(actions.renew)
    }

    @Test
    fun `paid order confirms tariff period expiry and preserved key`() {
        val order = json.decodeFromString<OrderSummary>(
            """{"id":1,"kind":"access_purchase","status":"delivered","tariffId":"regular","months":1,
                "amountRub":108,"paymentMethodId":"sbp","createdAt":"2026-11-01T10:00:00Z",
                "keyPreserved":true,"accessExpiresAt":"2026-12-01T10:00:00Z"}""".trimIndent(),
        )
        assertEquals("regular", order.tariffId)
        assertEquals(108, order.amountRub)
        assertTrue(order.keyPreserved)
        assertEquals("2026-12-01T10:00:00Z", order.accessExpiresAt)
    }
}
