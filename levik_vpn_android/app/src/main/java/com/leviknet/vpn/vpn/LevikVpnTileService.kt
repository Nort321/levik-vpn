package com.leviknet.vpn.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LevikVpnTileService : TileService() {
    private var serviceScope: CoroutineScope? = null
    private var stateJob: Job? = null

    private val container by lazy {
        (application as LevikVpnApplication).container
    }

    override fun onStartListening() {
        super.onStartListening()
        serviceScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope = scope
        stateJob = scope.launch {
            container.vpnController.state.collect { snapshot ->
                updateTileState(snapshot)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        serviceScope?.cancel()
        serviceScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val scope = serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            val current = container.vpnController.state.value
            when (current.state) {
                VpnConnectionState.PAUSED -> {
                    container.vpnController.resume()
                }
                VpnConnectionState.CONNECTED,
                VpnConnectionState.CONNECTING,
                VpnConnectionState.RECONNECTING -> {
                    container.vpnController.disconnect()
                }
                VpnConnectionState.DISCONNECTED,
                VpnConnectionState.ERROR,
                VpnConnectionState.LOCKDOWN,
                VpnConnectionState.STOPPING -> {
                    if (!container.vpnController.hasDisclosureConsent() ||
                        container.vpnController.permissionIntent() != null
                    ) {
                        openApp()
                    } else {
                        runCatching { container.vpnController.connect() }
                            .onFailure { openApp() }
                    }
                }
            }
        }
    }

    private fun updateTileState(snapshot: VpnSnapshot) {
        val tile = qsTile ?: return
        when (snapshot.state) {
            VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = snapshot.serverName ?: getString(R.string.status_connected)
                }
            }
            VpnConnectionState.PAUSED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val formatted = String.format(
                        java.util.Locale.US,
                        "%02d:%02d",
                        snapshot.pausedRemainingSeconds / 60,
                        snapshot.pausedRemainingSeconds % 60,
                    )
                    tile.subtitle = getString(R.string.status_paused, formatted)
                }
            }
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_connecting)
                }
            }
            VpnConnectionState.STOPPING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_stopping)
                }
            }
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_disconnected)
                }
            }
            VpnConnectionState.LOCKDOWN -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_lockdown)
                }
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
