package com.leviknet.vpn.vpn

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Why a subscription has no usable servers, from the name of Remnawave's stub link. */
enum class SubscriptionNotice { EXPIRED, TRAFFIC, DEVICE_LIMIT, SUSPENDED, NO_SERVERS }

/**
 * The subscription carries only stub links: Remnawave serves them for an
 * expired, limited or disabled user and for one whose plan has no ordinary
 * servers (Levik Game only). Connecting to one would "succeed" without internet.
 */
class SubscriptionNoticeException(val notice: SubscriptionNotice) :
    IllegalArgumentException("Subscription has no usable servers: $notice")

private const val PLACEHOLDER_ADDRESS = "0.0.0.0"
private const val PLACEHOLDER_PORT = 1

/** Remnawave's stub link: VLESS to 0.0.0.0:1 named after the reason. */
internal fun TunnelServer.isSubscriptionPlaceholder(): Boolean {
    if (engine != TunnelEngineKind.XRAY || tuicConfig != null || gameConfig != null) return false
    val settings = outbound["settings"] as? JsonObject ?: return false
    val endpoint = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject ?: settings
    val address = (endpoint["address"] as? JsonPrimitive)?.content
    val port = (endpoint["port"] as? JsonPrimitive)?.intOrNull
    return address == PLACEHOLDER_ADDRESS && port == PLACEHOLDER_PORT
}

/** Remark texts are set in the panel (subscription settings → custom remarks). */
internal fun subscriptionNotice(remark: String): SubscriptionNotice {
    val text = remark.lowercase(Locale.ROOT)
    return when {
        "подписка закончилась" in text || "expired" in text -> SubscriptionNotice.EXPIRED
        "трафик" in text || "traffic" in text -> SubscriptionNotice.TRAFFIC
        "лимит устройств" in text || "id устройства" in text || "device" in text -> SubscriptionNotice.DEVICE_LIMIT
        "приостановлен" in text || "disabled" in text -> SubscriptionNotice.SUSPENDED
        else -> SubscriptionNotice.NO_SERVERS
    }
}
