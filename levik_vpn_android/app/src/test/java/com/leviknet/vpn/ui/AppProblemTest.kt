package com.leviknet.vpn.ui

import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.network.*
import com.leviknet.vpn.data.canUseCachedProfileAfter
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
    fun `server device limit without details still offers recovery for the requested subscription`() {
        val failure = Json.decodeFromString<ApiFailure>("""{"code":"device_limit_reached","retryable":false}""")
        val problem = ApiException.Rejected(failure.code, failure.retryable, 409, failure.details)
            .toAppProblem(ProblemOperation.CONNECT, "single")
        val sub = subscription()
        val target = problemSubscription(problem, AppUiState(account = account(listOf(sub))))

        assertEquals(ProblemReason.DEVICE_LIMIT, problem.reason)
        assertEquals(ProblemOperation.CONNECT, problem.operation)
        assertEquals(sub, target)
        assertNull(problem.details)
        assertEquals(ProblemAction.DEVICES, problemActions(problem, target).first())
        assertTrue(problemActions(problem, target).contains(ProblemAction.SUBSCRIPTIONS))
    }

    @Test
    fun `cache cannot conceal authoritative account and slot errors`() {
        assertTrue(canUseCachedProfileAfter(ApiException.Network(IOException())))
        assertTrue(canUseCachedProfileAfter(ApiException.Rejected("temporarily_unavailable", true, 503)))
        assertFalse(canUseCachedProfileAfter(ApiException.Rejected("device_limit_reached", false, 409)))
        assertFalse(canUseCachedProfileAfter(ApiException.Rejected("subscription_not_found", false, 404)))
        assertFalse(canUseCachedProfileAfter(ApiException.InvalidResponse("invalid")))
    }

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
