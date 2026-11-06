package com.leviknet.vpn.ui

import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot

internal enum class SmartAutoUiStatus { READY, PREPARING, CONNECTING, CONNECTED, RECOVERING, PAUSED }

internal fun smartAutoUiStatus(vpn: VpnSnapshot, selecting: Boolean): SmartAutoUiStatus = when {
    vpn.state == VpnConnectionState.RECONNECTING || vpn.state == VpnConnectionState.LOCKDOWN -> SmartAutoUiStatus.RECOVERING
    vpn.state == VpnConnectionState.CONNECTED && (!vpn.mainSelectionCertain || vpn.trafficBlockedServerId != null) -> SmartAutoUiStatus.RECOVERING
    vpn.state == VpnConnectionState.CONNECTED -> SmartAutoUiStatus.CONNECTED
    vpn.state == VpnConnectionState.CONNECTING -> SmartAutoUiStatus.CONNECTING
    vpn.state == VpnConnectionState.PAUSED -> SmartAutoUiStatus.PAUSED
    selecting -> SmartAutoUiStatus.PREPARING
    else -> SmartAutoUiStatus.READY
}

internal fun showInitialServerLoading(loading: Boolean, hasCatalog: Boolean): Boolean = loading && !hasCatalog
