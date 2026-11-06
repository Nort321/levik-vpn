package com.leviknet.vpn.ui

import com.leviknet.vpn.core.network.DeviceSummary
import com.leviknet.vpn.core.network.MobileAccountResponse
import com.leviknet.vpn.core.network.SubscriptionActions
import com.leviknet.vpn.core.network.SubscriptionComponent
import com.leviknet.vpn.core.network.SubscriptionComponents
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.core.network.TrafficSummary
import com.leviknet.vpn.vpn.PreparedTunnelProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTrafficTest {
    @Test
    fun `disconnected list uses mobile allowance of displayed profile instead of another subscription`() {
        val mobile = TrafficSummary(20L, 100L)
        val subscription = subscription("displayed").copy(
            components = SubscriptionComponents(
                regular = SubscriptionComponent(TrafficSummary(500L, 0L), devices),
                mobile = SubscriptionComponent(mobile, devices),
            ),
        )
        val state = state(listOf(subscription("other"), subscription)).copy(selectedSubscriptionId = "other")

        assertEquals(mobile, state.mobileSubscriptionTraffic())
    }

    @Test
    fun `legacy subscription uses its aggregate traffic when components are absent`() {
        val subscription = subscription("displayed")
        assertEquals(subscription.traffic, state(listOf(subscription)).mobileSubscriptionTraffic())
    }

    @Test
    fun `missing account profile or matching subscription does not invent an allowance`() {
        val state = state(listOf(subscription("other")))
        assertNull(state.mobileSubscriptionTraffic())
        assertNull(state.copy(account = null).mobileSubscriptionTraffic())
        assertNull(state.copy(profile = null).mobileSubscriptionTraffic())
    }

    @Test
    fun `remaining allowance handles exhausted overdrawn and invalid counters without overflow`() {
        assertEquals(80L, TrafficSummary(20L, 100L).remainingBytes())
        assertEquals(0L, TrafficSummary(100L, 100L).remainingBytes())
        assertEquals(0L, TrafficSummary(150L, 100L).remainingBytes())
        assertEquals(100L, TrafficSummary(-1L, 100L).remainingBytes())
        assertEquals(0L, TrafficSummary(Long.MAX_VALUE, 100L).remainingBytes())
        assertEquals(Long.MAX_VALUE, TrafficSummary(Long.MIN_VALUE, Long.MAX_VALUE).remainingBytes())
    }

    private val devices = DeviceSummary(0, 1, emptyList())

    private fun subscription(id: String) = SubscriptionSummary(
        uuid = id, tariffId = "mobile", title = "VPN", status = "active",
        traffic = TrafficSummary(10L, 50L), devices = devices,
        actions = SubscriptionActions(true, true, true, true, true),
    )

    private fun state(subscriptions: List<SubscriptionSummary>): AppUiState {
        val account = Json.decodeFromString<MobileAccountResponse>("""{
            "ok":true,"user":{"userKey":"user","userLabel":"Test"},
            "trial":{"eligible":false,"status":"unavailable"},
            "referrals":null,"subscriptions":[],"orders":[],"freeProxy":{"available":false,"active":false}
        }""")
        return AppUiState(
            account = account.copy(subscriptions = subscriptions),
            profile = PreparedTunnelProfile(
                version = 1, profileId = "profile", subscriptionId = "displayed",
                issuedAt = "2026-11-06T00:00:00Z", servers = emptyList(),
            ),
        )
    }
}
