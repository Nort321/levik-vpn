package com.leviknet.vpn.ui

import com.leviknet.vpn.core.network.TrafficSummary

/** Use the subscription that owns the displayed servers, including before connecting. */
internal fun AppUiState.mobileSubscriptionTraffic(): TrafficSummary? {
    val subscriptionId = profile?.subscriptionId ?: return null
    val subscription = account?.subscriptions?.firstOrNull { it.uuid == subscriptionId } ?: return null
    return subscription.components?.mobile?.traffic ?: subscription.traffic
}

internal fun TrafficSummary.remainingBytes(): Long =
    (limitBytes.coerceAtLeast(0L) - usedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
