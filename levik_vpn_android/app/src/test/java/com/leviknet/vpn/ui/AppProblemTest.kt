package com.leviknet.vpn.ui

import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.network.*
import com.leviknet.vpn.data.canUseCachedProfileAfter
import com.leviknet.vpn.data.deviceSlotProblem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AppProblemTest {
    @Test
    fun `server and network failures have distinct recovery reasons`() {
        val cases = mapOf(
            "device_limit_reached" to ProblemReason.DEVICE_LIMIT,
            "subscription_not_found" to ProblemReason.SUBSCRIPTION,
            "traffic_limit_reached" to ProblemReason.TRAFFIC,
            "session_expired" to ProblemReason.SESSION,
            "login_expired" to ProblemReason.LOGIN,
            "integrity_rejected" to ProblemReason.ATTESTATION,
            "stale_request" to ProblemReason.CLOCK,
            "rate_limited" to ProblemReason.RATE_LIMIT,
            "trial_not_eligible" to ProblemReason.TRIAL,
            "payments_disabled" to ProblemReason.PAYMENT,
            "order_already_in_progress" to ProblemReason.ORDER,
            "invalid_profile_response" to ProblemReason.PROFILE,
            "profile_upstream_unavailable" to ProblemReason.SERVICE,
            "shield_not_supported" to ProblemReason.UNSUPPORTED,
            "replayed_request" to ProblemReason.REQUEST,
            "device_not_found" to ProblemReason.DEVICE_REVOKE,
        )
        cases.forEach { (code, expected) ->
            assertEquals(code, expected, ApiException.Rejected(code, false, 409).toAppProblem().reason)
        }
        assertEquals(ProblemReason.NETWORK, ApiException.Network(IOException()).toAppProblem().reason)
        assertEquals(ProblemReason.TIMEOUT, ApiException.Network(SocketTimeoutException()).toAppProblem().reason)
        assertEquals(ProblemReason.SERVICE, ApiException.Rejected("new_code", true, 503).toAppProblem().reason)
        assertEquals(ProblemReason.UNKNOWN, ApiException.Rejected("new_code", false, 403).toAppProblem().reason)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation does not produce a user error`() {
        CancellationException().toAppProblem()
    }

    @Test
    fun `full mobile component identifies the exact subscription and quota`() {
        val sub = multi()
        assertEquals(ApiProblemDetails("multi", "mobile", 1, 1), deviceSlotProblem(sub, "current"))
        assertEquals(ApiProblemDetails("multi", "regular", 1, 1), deviceSlotProblem(
            sub.copy(components = sub.components!!.copy(regular = component(1, 1, "regular:other"))), "current",
        ))
    }

    @Test
    fun `own registration in one component cannot hide a full other component`() {
        val sub = multi()
        assertNotNull(deviceSlotProblem(sub, "current"))
        assertNull(deviceSlotProblem(sub.copy(components = sub.components!!.copy(
            mobile = component(1, 1, "mobile:current"),
        )), "current"))
    }

    @Test
    fun `a truncated device list cannot prove that the current device is absent`() {
        assertNull(deviceSlotProblem(subscription().copy(devices = DeviceSummary(2, 2, listOf(DeviceItem("other", "Phone")))), "current"))
    }

    @Test
    fun `unlimited and freed slots allow a new device`() {
        assertNull(deviceSlotProblem(subscription().copy(devices = DeviceSummary(5, 0, emptyList())), "current"))
        assertNull(deviceSlotProblem(subscription().copy(devices = DeviceSummary(0, 1, emptyList())), "current"))
        assertNull(deviceSlotProblem(subscription().copy(devices = DeviceSummary(1, 1, listOf(DeviceItem("current", "Phone")))), "current"))
    }

    @Test
    fun `account target never silently switches to an unrelated subscription`() {
        val other = subscription().copy(uuid = "other")
        val state = AppUiState(account = account(listOf(other)), selectedSubscriptionId = "other")
        assertNull(problemSubscription(AppProblem(ProblemReason.DEVICE_LIMIT, subscriptionId = "missing"), state))
        assertEquals(other, problemSubscription(AppProblem(ProblemReason.DEVICE_LIMIT), state))
    }

    @Test
    fun `device limit recovery manages devices and respects distribution purchases`() {
        val actions = problemActions(AppProblem(ProblemReason.DEVICE_LIMIT), subscription())
        assertEquals(ProblemAction.DEVICES, actions.first())
        assertTrue(actions.contains(ProblemAction.SUBSCRIPTIONS))
        assertEquals(BuildConfig.EXTERNAL_PURCHASES_ENABLED, actions.contains(ProblemAction.PLANS))
        assertFalse(problemActions(AppProblem(ProblemReason.DEVICE_LIMIT), null).contains(ProblemAction.DEVICES))
    }

    @Test
    fun `client metadata is backward compatible and does not invent Android from a model`() {
        val old = Json.decodeFromString<DeviceItem>("""{"id":"one","label":"Pixel 10"}""")
        assertNull(old.connectionClient())
        assertEquals("Happ", old.copy(label = "Happ · Pixel 10").connectionClient())
        assertEquals("Levik VPN", old.copy(client = "Levik VPN").connectionClient())
    }

    @Test
    fun `error details survive decoding with or without the optional fields`() {
        assertNull(Json.decodeFromString<ApiFailure>("""{"code":"device_limit_reached","retryable":false}""").details)
        val failure = Json.decodeFromString<ApiFailure>("""{"code":"device_limit_reached","retryable":false,"details":{"subscriptionId":"multi","component":"mobile","used":1,"limit":1}}""")
        val problem = ApiException.Rejected(failure.code, false, 409, failure.details).toAppProblem(ProblemOperation.CONNECT)
        assertEquals("multi", problem.subscriptionId)
        assertEquals("mobile", problem.details?.component)
    }

    @Test
    fun `cache cannot conceal authoritative account and slot errors`() {
        assertTrue(canUseCachedProfileAfter(ApiException.Network(IOException())))
        assertTrue(canUseCachedProfileAfter(ApiException.Rejected("temporarily_unavailable", true, 503)))
        assertFalse(canUseCachedProfileAfter(ApiException.Rejected("device_limit_reached", false, 409)))
        assertFalse(canUseCachedProfileAfter(ApiException.Rejected("subscription_not_found", false, 404)))
        assertFalse(canUseCachedProfileAfter(ApiException.InvalidResponse("invalid")))
    }

    private fun component(used: Int, limit: Int, id: String) = SubscriptionComponent(
        TrafficSummary(0, 0), DeviceSummary(used, limit, listOf(DeviceItem(id, "Phone"))),
    )

    private fun multi() = subscription().copy(
        uuid = "multi",
        components = SubscriptionComponents(component(1, 2, "regular:current"), component(1, 1, "mobile:other")),
    )

    private fun subscription() = SubscriptionSummary(
        uuid = "single", tariffId = "solo", title = "VPN", status = "active",
        traffic = TrafficSummary(0, 0), devices = DeviceSummary(1, 1, listOf(DeviceItem("other", "Phone"))),
        actions = SubscriptionActions(true, true, true, true, true),
    )

    private fun account(subscriptions: List<SubscriptionSummary>): MobileAccountResponse {
        val base = Json.decodeFromString<MobileAccountResponse>("""{
            "ok":true,"user":{"userKey":"user","userLabel":"Test"},
            "trial":{"eligible":false,"status":"unavailable","expiresAt":null},
            "referrals":null,"subscriptions":[],"orders":[],"freeProxy":{"available":false,"active":false}
        }""")
        return base.copy(subscriptions = subscriptions)
    }
}
