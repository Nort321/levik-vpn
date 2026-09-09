package com.leviknet.vpn.ui

import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.ApiProblemDetails
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.vpn.VpnFailure
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException

enum class ProblemReason {
    NETWORK, TIMEOUT, SERVICE, PROFILE, DEVICE_LIMIT, SUBSCRIPTION, TRAFFIC,
    SESSION, LOGIN, ATTESTATION, CLOCK, RATE_LIMIT, TRIAL, PAYMENT, ORDER,
    PERMISSION, NOTIFICATIONS, LOCATION, DEVICE_REVOKE, UNSUPPORTED, REQUEST, UNKNOWN,
    VPN_CORE, VPN_NETWORK, VPN_NETWORK_REQUIREMENT,
}

enum class ProblemOperation { ACCOUNT, CONNECT, SERVERS, SELECT_SUBSCRIPTION, LOGIN, DEVICES, PURCHASE }

data class AppProblem(
    val reason: ProblemReason,
    val operation: ProblemOperation = ProblemOperation.ACCOUNT,
    val subscriptionId: String? = null,
    val details: ApiProblemDetails? = null,
)

internal fun Throwable.toAppProblem(
    operation: ProblemOperation = ProblemOperation.ACCOUNT,
    subscriptionId: String? = null,
): AppProblem {
    if (this is CancellationException) throw this
    val reason = when (this) {
        is ApiException.Unauthorized -> ProblemReason.SESSION
        is ApiException.AttestationUnavailable -> ProblemReason.ATTESTATION
        is ApiException.Network -> if (cause is SocketTimeoutException) ProblemReason.TIMEOUT else ProblemReason.NETWORK
        is SocketTimeoutException -> ProblemReason.TIMEOUT
        is IOException -> ProblemReason.NETWORK
        is ApiException.InvalidResponse, is IllegalArgumentException -> ProblemReason.PROFILE
        is ApiException.Rejected -> when (code) {
            "device_limit_reached", "hwid_limit_reached", "device_limit_exceeded" -> ProblemReason.DEVICE_LIMIT
            "subscription_not_found", "subscription_expired", "subscription_inactive" -> ProblemReason.SUBSCRIPTION
            "traffic_limit_reached", "traffic_exhausted", "traffic_limit_exceeded" -> ProblemReason.TRAFFIC
            "authentication_required", "session_expired", "account_not_found", "invalid_device_binding" -> ProblemReason.SESSION
            "login_denied", "login_expired", "authorization_denied", "authorization_expired" -> ProblemReason.LOGIN
            "integrity_required", "invalid_integrity_token", "integrity_rejected",
            "integrity_verifier_unavailable", "integrity_verification_unavailable" -> ProblemReason.ATTESTATION
            "stale_request" -> ProblemReason.CLOCK
            "profile_rate_limited", "rate_limited", "trial_rate_limited" -> ProblemReason.RATE_LIMIT
            "trial_not_eligible", "trial_already_used", "trial_unavailable" -> ProblemReason.TRIAL
            "payment_not_available", "order_payment_unavailable", "payment_url_unavailable", "payments_disabled", "payment_method_unavailable", "payment_provider_unavailable" -> ProblemReason.PAYMENT
            "order_already_in_progress", "order_not_found", "order_not_allowed", "traffic_addon_unavailable", "slot_addon_unavailable", "renewal_unavailable" -> ProblemReason.ORDER
            "profile_upstream_unavailable", "temporarily_unavailable", "bridge_unavailable",
            "account_unavailable", "login_unavailable", "session_binding_failed" -> ProblemReason.SERVICE
            "profile_unavailable", "invalid_profile_response", "profile_too_large", "invalid_profile_expiry",
            "relay_profile_binding_mismatch", "invalid_relay_profile" -> ProblemReason.PROFILE
            "device_not_found", "relay_device_not_registered" -> ProblemReason.DEVICE_REVOKE
            "relay_not_available", "shield_not_supported" -> ProblemReason.UNSUPPORTED
            "invalid_request_signature", "invalid_public_key", "invalid_device_id", "invalid_request_proof",
            "invalid_request_target", "invalid_json_request", "invalid_request_body", "replayed_request" -> ProblemReason.REQUEST
            else -> when {
                status == 429 -> ProblemReason.RATE_LIMIT
                status == 408 || status == 504 -> ProblemReason.TIMEOUT
                status >= 500 -> ProblemReason.SERVICE
                else -> ProblemReason.UNKNOWN
            }
        }
        else -> ProblemReason.UNKNOWN
    }
    val details = (this as? ApiException.Rejected)?.details
    return AppProblem(reason, operation, subscriptionId ?: details?.subscriptionId, details)
}

internal fun UiMessage.asProblem(): AppProblem? = when (this) {
    UiMessage.SUBSCRIPTION_UPDATED, UiMessage.DEVICE_REVOKED_SUCCESS, UiMessage.SERVER_PING_UNAVAILABLE,
    UiMessage.TRAFFIC_HISTORY_CLEARED, UiMessage.TRAFFIC_HISTORY_EXPORTED -> null
    else -> AppProblem(when (this) {
        UiMessage.SESSION_EXPIRED -> ProblemReason.SESSION
        UiMessage.SUBSCRIPTION_REQUIRED -> ProblemReason.SUBSCRIPTION
        UiMessage.PROFILE_UNAVAILABLE -> ProblemReason.PROFILE
        UiMessage.DEVICE_LIMIT_REACHED -> ProblemReason.DEVICE_LIMIT
        UiMessage.RATE_LIMITED -> ProblemReason.RATE_LIMIT
        UiMessage.VPN_PERMISSION_DENIED -> ProblemReason.PERMISSION
        UiMessage.NOTIFICATION_PERMISSION_DENIED -> ProblemReason.NOTIFICATIONS
        UiMessage.LOCATION_PERMISSION_DENIED -> ProblemReason.LOCATION
        UiMessage.LOGIN_DENIED -> ProblemReason.LOGIN
        UiMessage.ATTESTATION_UNAVAILABLE -> ProblemReason.ATTESTATION
        UiMessage.SERVER_PING_UNAVAILABLE -> ProblemReason.VPN_NETWORK
        UiMessage.DEVICE_REVOKE_FAILED -> ProblemReason.DEVICE_REVOKE
        UiMessage.PAYMENT_OPEN_FAILED, UiMessage.PAYMENT_NOT_AVAILABLE -> ProblemReason.PAYMENT
        UiMessage.PAYMENT_ALREADY_PENDING -> ProblemReason.ORDER
        else -> ProblemReason.UNKNOWN
    })
}

internal fun VpnFailure.asProblem(): AppProblem = AppProblem(
    reason = when (this) {
        VpnFailure.CORE_UNAVAILABLE -> ProblemReason.VPN_CORE
        VpnFailure.INVALID_PROFILE -> ProblemReason.PROFILE
        VpnFailure.PERMISSION_REVOKED -> ProblemReason.PERMISSION
        VpnFailure.NETWORK -> ProblemReason.VPN_NETWORK
        VpnFailure.NETWORK_REQUIREMENT -> ProblemReason.VPN_NETWORK_REQUIREMENT
    },
    operation = ProblemOperation.CONNECT,
)

internal fun problemSubscription(problem: AppProblem, state: AppUiState): SubscriptionSummary? {
    val subscriptions = state.account?.subscriptions.orEmpty()
    // An explicit failed target must never be replaced by another subscription.
    val targetId = problem.subscriptionId ?: state.selectedSubscriptionId ?: state.profile?.subscriptionId
    return if (targetId != null) subscriptions.firstOrNull { it.uuid == targetId }
    else subscriptions.singleOrNull()
}
