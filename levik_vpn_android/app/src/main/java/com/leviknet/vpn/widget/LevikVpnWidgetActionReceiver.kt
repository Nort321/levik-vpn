package com.leviknet.vpn.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.vpn.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LevikVpnWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_VPN) return
        val app = context.applicationContext as? LevikVpnApplication ?: return
        val container = app.container
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val current = container.vpnController.state.value
                when (current.state) {
                    VpnConnectionState.PAUSED -> container.vpnController.resume()
                    VpnConnectionState.CONNECTED,
                    VpnConnectionState.CONNECTING,
                    VpnConnectionState.RECONNECTING -> container.vpnController.disconnect()
                    VpnConnectionState.DISCONNECTED,
                    VpnConnectionState.ERROR,
                    VpnConnectionState.LOCKDOWN,
                    VpnConnectionState.STOPPING -> connectOrOpenApp(context, app)
                }
                LevikVpnWidgetProvider.updateAllWidgets(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun connectOrOpenApp(context: Context, app: LevikVpnApplication) {
        val controller = app.container.vpnController
        if (!controller.hasDisclosureConsent() || controller.permissionIntent() != null) {
            openApp(context)
            return
        }
        runCatching { controller.connect() }
            .onFailure { openApp(context) }
    }

    private fun openApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }

    companion object {
        const val ACTION_TOGGLE_VPN = "com.leviknet.vpn.action.WIDGET_TOGGLE_VPN"
    }
}
