package com.leviknet.vpn.core.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.leviknet.vpn.vpn.LevikVpnService
import java.net.Inet4Address
import java.net.NetworkInterface

object SharingController {
    const val START_HOST = "com.leviknet.vpn.sharing.START_HOST"
    const val STOP_HOST = "com.leviknet.vpn.sharing.STOP_HOST"
    const val CONNECT_GUEST = "com.leviknet.vpn.sharing.CONNECT_GUEST"
    const val DISCONNECT_GUEST = "com.leviknet.vpn.sharing.DISCONNECT_GUEST"
    const val ADDRESS = "sharing_address"
    const val INVITATION = "sharing_invitation"

    fun startHost(context: Context, address: String) {
        require(address in SharingNetworks.localAddresses()) { "Выберите адрес включённой точки доступа" }
        send(context, START_HOST) { putExtra(ADDRESS, address) }
    }
    fun stopHost(context: Context) = send(context, STOP_HOST)
    fun connectGuest(context: Context, invitation: String) {
        SharingInvitation.parse(invitation)
        send(context, CONNECT_GUEST) { putExtra(INVITATION, invitation.trim()) }
    }
    fun disconnectGuest(context: Context) = send(context, DISCONNECT_GUEST)
    private fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(context, Intent(context, LevikVpnService::class.java).setAction(action).apply(configure))
    }
}

object SharingNetworks {
    /** Sticky system broadcast (TetheringManager.ACTION_TETHER_STATE_CHANGED, not public SDK). */
    const val TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private const val EXTRA_ACTIVE_TETHER = "tetherArray"

    /** Best effort: false when the OS omits the extra, so callers only lose an optional hint. */
    fun isTethering(intent: Intent?): Boolean =
        intent?.getStringArrayListExtra(EXTRA_ACTIVE_TETHER)?.any { it.isNotBlank() } == true

    private val excluded = Regex("tun|tap|wg|ppp|ipsec|levik|vpn|rmnet|ccmni|pdp|clat|v4-|dummy|lo$", RegexOption.IGNORE_CASE)
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !excluded.containsMatchIn(it.name) }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }.filter(::isPrivateIpv4).distinct().take(8)
    }.getOrDefault(emptyList())
}
