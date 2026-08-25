package com.leviknet.vpn.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthChallengeRequest(
    val accountActivationSupported: Boolean,
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
    val accountActivationSupported: Boolean = false,
    val activationCode: String? = null,
    val activationUriComplete: String? = null,
    val verificationCode: String? = null,
    val verificationUriComplete: String? = null,
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
class TrialActivationRequest

@Serializable
data class TrialActivationResponse(
    val ok: Boolean,
    val subscriptionId: String,
)

@Serializable
data class MobileAccountResponse(
    val ok: Boolean,
    val user: AccountUser,
    val trial: TrialSummary,
    val referrals: ReferralSummary?,
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
    val components: SubscriptionComponents? = null,
    val shield: ShieldSummary = ShieldSummary(),
    val actions: SubscriptionActions,
)

@Serializable
data class SubscriptionComponents(
    val regular: SubscriptionComponent,
    val mobile: SubscriptionComponent,
)

@Serializable
data class SubscriptionComponent(
    val traffic: TrafficSummary,
    val devices: DeviceSummary,
)

@Serializable
data class ShieldSummary(
    val supported: Boolean = false,
    val enabled: Boolean = false,
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
    val paymentUrl: String? = null,
)

@Serializable
data class CatalogResponse(
    val ok: Boolean,
    val tariffs: List<CatalogTariff>,
    val paymentMethods: List<CatalogPaymentMethod>,
    val addons: List<CatalogAddon> = emptyList(),
)

@Serializable
data class CatalogTariff(
    val id: String,
    val title: String,
    val description: String,
    val purchaseEnabled: Boolean,
    val trafficLimitBytes: Long,
    val deviceLimit: Int,
    val periods: List<CatalogPeriod>,
)

@Serializable
data class CatalogPeriod(
    val months: Int,
    val title: String,
    val amountRub: Int,
)

@Serializable
data class CatalogPaymentMethod(
    val id: String,
    val title: String,
    val feePercent: Double,
)

@Serializable
data class CatalogAddon(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val amountRub: Int,
    val deviceDelta: Int,
    val trafficDeltaBytes: Long,
)

@Serializable
data class CreateOrderRequest(
    val kind: String,
    val subscriptionId: String? = null,
    val tariffId: String? = null,
    val months: Int? = null,
    val paymentMethodId: String,
)

@Serializable
data class CreateOrderResponse(
    val ok: Boolean,
    val order: OrderSummary,
)

@Serializable
data class FreeProxySummary(
    val available: Boolean,
    val active: Boolean,
)

@Serializable
data class FreeProxyResponse(
    val ok: Boolean,
    val link: String? = null,
    val deviceLimit: Int? = null,
    val rateLimitMbps: Int? = null,
    val message: String? = null,
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
data class ShieldUpdateRequest(
    val subscriptionId: String,
    val enabled: Boolean,
)

@Serializable
data class ShieldUpdateResponse(
    val ok: Boolean,
    val enabled: Boolean,
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

@Serializable
data class LevikStatusSnapshot(
    val servers: List<LevikServerStatus> = emptyList(),
    val fetchedAt: String = "",
    val source: String = "stale",
    val controlLatencyMs: Long = 0,
)

@Serializable
data class LevikServerStatus(
    val id: String,
    val countryCode: String,
    val state: String,
    val load: Double? = null,
    val uptimeSeconds: Double? = null,
    val trafficUsedBytes: Long = 0,
    val lastStatusChange: String? = null,
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
