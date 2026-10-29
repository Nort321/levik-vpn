package com.leviknet.vpn.ui

import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot
import com.leviknet.vpn.vpn.HotSwitchPhase

internal enum class ManualServerSelection { SAVE_ONLY, HOT_SWITCH, RECONNECT }

internal fun manualServerSelection(vpn: VpnSnapshot, serverId: String): ManualServerSelection = when {
    vpn.state == VpnConnectionState.CONNECTED && serverId in vpn.hotSwitchServerIds -> ManualServerSelection.HOT_SWITCH
    vpn.state in setOf(VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING) ->
        ManualServerSelection.RECONNECT
    else -> ManualServerSelection.SAVE_ONLY
}

/** A persisted preference or a pending request is not a running-route acknowledgement. */
internal fun confirmedServerSelection(
    currentId: String?,
    vpn: VpnSnapshot,
    profileSubscriptionId: String?,
    availableIds: Set<String>,
): String? = vpn.serverId?.takeIf {
    vpn.state == VpnConnectionState.CONNECTED && vpn.mainSelectionCertain &&
        vpn.subscriptionId == profileSubscriptionId && it in availableIds
} ?: currentId

internal enum class ServerSwitchHint { HOT_SWITCH, RECONNECT, MAY_RECONNECT }

internal fun serverSwitchHint(vpn: VpnSnapshot, serverId: String): ServerSwitchHint? {
    if (vpn.state != VpnConnectionState.CONNECTED) return null
    if (vpn.mainSelectionCertain && vpn.serverId == serverId && serverId in vpn.hotSwitchServerIds) return null
    return if (serverId in vpn.hotSwitchServerIds) ServerSwitchHint.HOT_SWITCH else ServerSwitchHint.RECONNECT
}

internal fun countrySwitchHint(vpn: VpnSnapshot, serverIds: List<String>): ServerSwitchHint? {
    if (vpn.state != VpnConnectionState.CONNECTED) return null
    vpn.serverId?.takeIf(serverIds::contains)?.let { return serverSwitchHint(vpn, it) }
    return if (serverIds.isNotEmpty() && serverIds.all(vpn.hotSwitchServerIds::contains)) ServerSwitchHint.HOT_SWITCH
    else ServerSwitchHint.MAY_RECONNECT
}

internal fun reconnectAllTarget(vpn: VpnSnapshot): String? =
    if (vpn.state == VpnConnectionState.CONNECTED && vpn.hotSwitch?.phase != HotSwitchPhase.PREPARING) {
        vpn.hotSwitch?.targetServerId ?: vpn.serverId
    } else null

/** In-flight RPC uncertainty is progress; a terminal unknown route needs recovery guidance. */
internal fun displayedHotSwitchPhase(vpn: VpnSnapshot): HotSwitchPhase? {
    if (vpn.state != VpnConnectionState.CONNECTED) return null
    if (vpn.hotSwitch?.phase == HotSwitchPhase.PREPARING) return HotSwitchPhase.PREPARING
    if (!vpn.mainSelectionCertain) return HotSwitchPhase.UNCERTAIN
    return vpn.hotSwitch?.phase
}
