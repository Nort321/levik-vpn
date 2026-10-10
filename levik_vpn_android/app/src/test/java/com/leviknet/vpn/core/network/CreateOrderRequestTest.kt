package com.leviknet.vpn.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOrderRequestTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `an extra traffic pack is sent as a traffic order with its id`() {
        val request = CreateOrderRequest.of("traffic_addon_50", "sub", null, null, "sbp")
        assertEquals(
            """{"kind":"traffic_addon","subscriptionId":"sub","paymentMethodId":"sbp","addonId":"traffic_addon_50"}""",
            json.encodeToString(CreateOrderRequest.serializer(), request),
        )
    }

    @Test
    fun `the base pack and other orders carry no addon id`() {
        val base = CreateOrderRequest.of("traffic_addon", "sub", null, null, "sbp")
        assertEquals(
            """{"kind":"traffic_addon","subscriptionId":"sub","paymentMethodId":"sbp"}""",
            json.encodeToString(CreateOrderRequest.serializer(), base),
        )
        val slot = CreateOrderRequest.of("slot_addon", "sub", null, null, "sbp")
        assertEquals("slot_addon", slot.kind)
        assertEquals(null, slot.addonId)
    }

    @Test
    fun `only well formed pack ids count as traffic packs`() {
        assertTrue(CreateOrderRequest.isTrafficPack("traffic_addon"))
        assertTrue(CreateOrderRequest.isTrafficPack("traffic_addon_50"))
        assertFalse(CreateOrderRequest.isTrafficPack("slot_addon"))
        assertFalse(CreateOrderRequest.isTrafficPack("traffic_addon_../x"))
        assertEquals("traffic_addon_X", CreateOrderRequest.of("traffic_addon_X", "s", null, null, "sbp").kind)
    }
}
