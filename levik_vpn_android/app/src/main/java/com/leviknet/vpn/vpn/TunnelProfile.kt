package com.leviknet.vpn.vpn

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TunnelProfile(
    val version: Int,
    val engine: TunnelEngineKind = TunnelEngineKind.XRAY,
    val profileId: String,
    val subscriptionId: String,
    val issuedAt: String,
    val subscriptionExpiresAt: String? = null,
    val source: TunnelProfileSource? = null,
    val bootstrap: RelayBootstrap? = null,
    val routing: TunnelRouting? = null,
)

@Serializable
data class TunnelProfileSource(
    val mediaType: String,
    val content: String,
)

@Serializable
enum class TunnelEngineKind {
    @SerialName("xray")
    XRAY,

    @SerialName("levik-relay")
    LEVIK_RELAY,
}

@Serializable
enum class TunnelServerCategory {
    @SerialName("regular")
    REGULAR,

    @SerialName("mobile")
    MOBILE,

    @SerialName("mobile-allowlist")
    MOBILE_ALLOWLIST,
}

@Serializable
enum class TunnelNetworkRequirement {
    @SerialName("any")
    ANY,

    @SerialName("cellular-allowlist")
    CELLULAR_ALLOWLIST,
}

@Serializable
data class RelayBootstrap(
    val version: Int,
    val protocol: String,
    val policy: RelayPolicy,
    val entitlementId: String,
    val deviceId: String,
    val credentialId: String,
    val accessToken: String,
    val expiresAt: String,
    val nodes: List<RelayNode>,
)

@Serializable
data class RelayPolicy(
    val version: Int,
    val deviceSlots: RelayDeviceSlotsPolicy,
    val trafficAccounting: RelayTrafficAccountingPolicy,
)

@Serializable
enum class RelayDeviceSlotsPolicy {
    @SerialName("remnawave-hwid-shared")
    REMNAWAVE_HWID_SHARED,
}

@Serializable
enum class RelayTrafficAccountingPolicy {
    @SerialName("relay-separate")
    RELAY_SEPARATE,
}

@Serializable
data class RelayNode(
    val id: String,
    val displayName: String,
    val countryCode: String,
    val host: String,
    val port: Int,
    val turnFrontSni: String,
    val transport: RelayTransport,
    val serverPublicKey: String,
    val turnHashes: List<String>,
)

@Serializable
enum class RelayTransport {
    @SerialName("turn-dtls")
    TURN_DTLS,
}

@Serializable
data class TunnelRouting(
    val policyVersion: Int = 0,
    val directCidrs: List<String> = emptyList(),
    val directDomains: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(),
)

@Serializable
data class RelayServerConfig(
    val bootstrap: RelayBootstrap,
    val node: RelayNode,
    val routing: TunnelRouting? = null,
)

@Serializable
data class PreparedTunnelProfile(
    val version: Int,
    val profileId: String,
    val subscriptionId: String,
    val issuedAt: String,
    val subscriptionExpiresAt: String? = null,
    val relayCredentialExpiresAt: String? = null,
    val servers: List<TunnelServer>,
    val directCidrs: List<String> = emptyList(),
    val directDomains: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(),
)

@Serializable
data class TunnelServer(
    val id: String,
    val tag: String,
    val name: String,
    val countryCode: String,
    val outbound: JsonObject,
    val engine: TunnelEngineKind = TunnelEngineKind.XRAY,
    // Null is reserved for v1 prepared-profile caches created before categories were explicit.
    val category: TunnelServerCategory? = null,
    val networkRequirement: TunnelNetworkRequirement = TunnelNetworkRequirement.ANY,
    val relayConfig: RelayServerConfig? = null,
)

fun TunnelServer.effectiveCategory(): TunnelServerCategory = category ?: legacyCategory()

fun TunnelServer.isMobileServer(): Boolean = effectiveCategory() in setOf(
    TunnelServerCategory.MOBILE,
    TunnelServerCategory.MOBILE_ALLOWLIST,
)

fun TunnelServer.isStandardMobileServer(): Boolean =
    effectiveCategory() == TunnelServerCategory.MOBILE

fun TunnelServer.isAllowlistMobileServer(): Boolean =
    effectiveCategory() == TunnelServerCategory.MOBILE_ALLOWLIST

/** Runtime-only routing policy. LTE is deliberately absent from user settings and UI. */
enum class EffectiveRoutingProfile {
    USER_SELECTED,
    LTE,
}

fun TunnelServer.effectiveRoutingProfile(): EffectiveRoutingProfile =
    if (isMobileServer()) EffectiveRoutingProfile.LTE else EffectiveRoutingProfile.USER_SELECTED

fun TunnelServer.hasUnlimitedTraffic(): Boolean =
    !isStandardMobileServer()

private fun TunnelServer.legacyCategory(): TunnelServerCategory =
    legacyServerCategory(name, tag)

internal fun legacyServerCategory(name: String, tag: String): TunnelServerCategory {
    val normalizedName = name.uppercase(Locale.ROOT)
    val normalizedTag = tag.uppercase(Locale.ROOT)
    val mobile = normalizedName.contains("LTE") ||
        normalizedName.contains("MOBILE") ||
        normalizedName.contains("МОБИЛЬН") ||
        normalizedTag.contains("LTE") ||
        normalizedTag.contains("MOBILE")
    return if (mobile) TunnelServerCategory.MOBILE else TunnelServerCategory.REGULAR
}

fun TunnelServer.isRussianServer(): Boolean =
    countryCode.trim().equals("RU", ignoreCase = true)

fun TunnelServer.isEligibleForAutomaticSelection(): Boolean =
    engine == TunnelEngineKind.XRAY && !isRussianServer()
