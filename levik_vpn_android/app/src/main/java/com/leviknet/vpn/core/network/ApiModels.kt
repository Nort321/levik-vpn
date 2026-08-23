package com.leviknet.vpn.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthChallengeRequest(
    val publicKeySpki: String,
    val deviceLabel: String,
    val deviceModel: String,
    val deviceOs: String,
    val appVersion: String,
    val requestSigningAlgorithm: String,
    val profileEncryptionAlgorithm: String,
)

@Serializable
data class AuthChallengeResponse(
    val ok: Boolean,
    val loginToken: String,
    val verificationCode: String,
    val verificationUriComplete: String,
    val pollIntervalSeconds: Int,
    val expiresAt: String,
)

@Serializable
data class AuthStatusRequest(
    val loginToken: String,
)

@Serializable
data class AuthStatusResponse(
    val ok: Boolean,
    val state: String,
    val pollIntervalSeconds: Int = 2,
    val accessToken: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class MobileAccountResponse(
    val ok: Boolean,
    val user: AccountUser,
    val trial: TrialSummary,
    val referrals: ReferralSummary,
    val subscriptions: List<SubscriptionSummary>,
    val orders: List<OrderSummary>,
    val freeProxy: FreeProxySummary,
)

@Serializable
data class AccountUser(
    val userKey: String,
    val userLabel: String,
)

@Serializable
data class TrialSummary(
    val eligible: Boolean,
    val status: String,
    val expiresAt: String? = null,
)

@Serializable
data class ReferralSummary(
    val invited: Int,
    val rewarded: Int,
    val discountPercent: Int,
    val rewardDays: Int,
    val referralLink: String,
)

@Serializable
data class SubscriptionSummary(
    val uuid: String,
    val tariffId: String,
    val title: String,
    val status: String,
    val expireAt: String? = null,
    val traffic: TrafficSummary,
    val devices: DeviceSummary,
    val actions: SubscriptionActions,
)

@Serializable
data class TrafficSummary(
    val usedBytes: Long,
    val limitBytes: Long,
)

@Serializable
data class DeviceSummary(
    val used: Int,
    val limit: Int,
    val items: List<DeviceItem>,
)

@Serializable
data class DeviceItem(
    val id: String,
    val label: String,
)

@Serializable
data class SubscriptionActions(
    val renew: Boolean,
    val rotateKey: Boolean,
    val revokeDevice: Boolean,
    val slotAddon: Boolean,
    val trafficAddon: Boolean,
)

@Serializable
data class OrderSummary(
    val id: Long,
    val kind: String,
    val status: String,
    val tariffId: String? = null,
    val months: Int,
    val amountRub: Int,
    val paymentMethodId: String,
    val createdAt: String,
)

@Serializable
data class FreeProxySummary(
    val available: Boolean,
    val active: Boolean,
)

@Serializable
data class TunnelProfileRequest(
    val subscriptionId: String,
)

@Serializable
data class TunnelProfileResponse(
    val ok: Boolean,
    val profile: TunnelProfileEnvelope,
)

@Serializable
data class TunnelProfileEnvelope(
    val algorithm: String,
    val encryptedKey: String,
    val iv: String,
    val ciphertext: String,
    val aad: String,
)

@Serializable
data class LogoutResponse(
    val ok: Boolean,
)

@Serializable
data class RevokeDeviceRequest(
    val subscriptionId: String,
    val deviceId: String,
)

@Serializable
data class SimpleSuccessResponse(
    val ok: Boolean = true,
)

@Serializable
data class ApiFailureResponse(
    val ok: Boolean = false,
    val error: ApiFailure,
)

@Serializable
data class ApiFailure(
    val code: String,
    val retryable: Boolean,
)

@Serializable
data class AppUpdateResponse(
    val ok: Boolean,
    val update: AppUpdateDto? = null,
)

@Serializable
data class AppUpdateDto(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minSupportedVersionCode: Int = 1,
    val downloadUrl: String,
    val sha256: String? = null,
    val titleRu: String? = null,
    val titleEn: String? = null,
    val changelogRu: String? = null,
    val changelogEn: String? = null,
    val forceUpdate: Boolean = false,
)

@Serializable
data class IpCheckResponse(
    val ok: Boolean = true,
    val address: String = "",
    val isIpv6: Boolean = false,
    val countryCode: String? = null,
    val countryName: String? = null,
    val region: String? = null,
    val city: String? = null,
    val asn: Long? = null,
    val org: String? = null,
    val provider: String? = null,
    val protection: String? = null,
    val isProtected: Boolean = false,
)

@Serializable
data class CreateNoteRequest(
    val id: String,
    val keyCommitment: String,
    val iv: String,
    val ciphertext: String,
    val expiresInDays: Int = 7,
)

@Serializable
data class CreateNoteResponse(
    val ok: Boolean,
    val expiresAt: String? = null,
    val message: String? = null,
)

@Serializable
data class BrowserCheckReportRequest(
    val mode: String = "diagnostic",
    val measuredAt: String,
    val results: List<BrowserCheckReportServiceResult>,
)

@Serializable
data class BrowserCheckReportServiceResult(
    val serviceSlug: String,
    val checks: List<BrowserCheckReportEndpointResult>,
)

@Serializable
data class BrowserCheckReportEndpointResult(
    val id: String,
    val reachable: Boolean,
    val latencyMs: Long?,
)

enum class LoginState {
    PENDING,
    AUTHENTICATED,
    EXPIRED,
    DENIED,
    ;

    companion object {
        fun fromWire(value: String): LoginState = when (value.lowercase()) {
            "pending" -> PENDING
            "authenticated" -> AUTHENTICATED
            "expired" -> EXPIRED
            "denied" -> DENIED
            else -> throw ApiException.InvalidResponse("Unknown authentication state")
        }
    }
}

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : ApiException("Network request failed", cause)
    class Unauthorized : ApiException("Session is not authorized")
    class Rejected(val code: String, val retryable: Boolean, val status: Int) :
        ApiException("API rejected the request: $code")

    class InvalidResponse(message: String, cause: Throwable? = null) :
        ApiException(message, cause)

    class AttestationUnavailable(cause: Throwable? = null) :
        ApiException("Play Integrity attestation is unavailable", cause)
}
