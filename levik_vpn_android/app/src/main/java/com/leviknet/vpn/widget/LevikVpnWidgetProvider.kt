package com.leviknet.vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot
import com.leviknet.vpn.vpn.VpnStateStore

class LevikVpnWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snapshot = VpnStateStore.state.value
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, snapshot)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            snapshot: VpnSnapshot,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_levik_vpn)

            // Open app on background click
            val openAppIntent = Intent(context, MainActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

            // Toggle VPN button
            val toggleIntent = Intent(context, LevikVpnWidgetActionReceiver::class.java).apply {
                action = LevikVpnWidgetActionReceiver.ACTION_TOGGLE_VPN
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_toggle_button, togglePendingIntent)

            val statusText = when (snapshot.state) {
                VpnConnectionState.CONNECTED -> context.getString(R.string.status_connected)
                VpnConnectionState.PAUSED -> context.getString(R.string.status_paused, "")
                VpnConnectionState.CONNECTING -> context.getString(R.string.status_connecting)
                VpnConnectionState.RECONNECTING -> context.getString(R.string.status_reconnecting)
                VpnConnectionState.STOPPING -> context.getString(R.string.status_stopping)
                VpnConnectionState.ERROR -> context.getString(R.string.status_error)
                VpnConnectionState.DISCONNECTED -> context.getString(R.string.status_disconnected)
                VpnConnectionState.LOCKDOWN -> context.getString(R.string.status_lockdown)
            }
            views.setTextViewText(R.id.widget_status, statusText)
            views.setTextViewText(
                R.id.widget_server,
                snapshot.serverName ?: context.getString(R.string.select_server),
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, LevikVpnWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            val snapshot = VpnStateStore.state.value
            for (id in allWidgetIds) {
                updateWidget(context, appWidgetManager, id, snapshot)
            }
        }
    }
}
