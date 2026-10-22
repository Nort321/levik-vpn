package com.leviknet.vpn.vpn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPlaceholdersTest {
    @Test
    fun `Remnawave stub links are recognized in both outbound shapes`() {
        assertTrue(vnextServer("Серверы временно недоступны", "0.0.0.0", 1).isSubscriptionPlaceholder())
        assertTrue(flatServer("0.0.0.0", 1).isSubscriptionPlaceholder())
        assertFalse(vnextServer("Prime", "0.0.0.0", 443).isSubscriptionPlaceholder())
        assertFalse(vnextServer("Prime", "94.156.114.70", 1).isSubscriptionPlaceholder())
    }

    @Test
    fun `stub names map to the reason shown to the user`() {
        assertEquals(SubscriptionNotice.EXPIRED, subscriptionNotice("Подписка закончилась · продлите в боте"))
        assertEquals(SubscriptionNotice.TRAFFIC, subscriptionNotice("Трафик закончился · откройте бота"))
        assertEquals(SubscriptionNotice.SUSPENDED, subscriptionNotice("Доступ приостановлен · напишите в поддержку"))
        assertEquals(SubscriptionNotice.DEVICE_LIMIT, subscriptionNotice("Достигнут лимит устройств"))
        assertEquals(SubscriptionNotice.NO_SERVERS, subscriptionNotice("Серверы временно недоступны"))
    }

    private fun vnextServer(name: String, address: String, port: Int) = TunnelServer(
        id = name,
        tag = name,
        name = name,
        countryCode = "XX",
        outbound = buildJsonObject {
            put("protocol", "vless")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", address)
                        put("port", port)
                        put("users", buildJsonArray { add(JsonObject(mapOf())) })
                    })
                })
            })
        },
    )

    private fun flatServer(address: String, port: Int) = TunnelServer(
        id = "flat",
        tag = "flat",
        name = "flat",
        countryCode = "XX",
        outbound = buildJsonObject {
            put("protocol", "vless")
            put("settings", buildJsonObject {
                put("address", address)
                put("port", port)
                put("id", "00000000-0000-0000-0000-000000000000")
            })
        },
    )
}
