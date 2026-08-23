package com.leviknet.vpn.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.leviknet.vpn.data.AppSettings
import com.leviknet.vpn.core.logger.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watches Wi-Fi transitions and connects the VPN automatically whenever the
 * device joins a network whose SSID is not in the trusted list.
 *
 * Reading the SSID requires ACCESS_FINE_LOCATION on API 29+; when the
 * permission is missing the monitor stays dormant.
 */
class WifiAutoConnectMonitor(
    private val context: Context,
    private val settings: AppSettings,
    private val vpnController: VpnController,
    private val scope: CoroutineScope,
) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val lastAttemptAtBySsid = ConcurrentHashMap<String, Long>()
    private var started = false

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkEvent(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            handleNetworkEvent(network, networkCapabilities)
        }
    }

    fun start() {
        if (started) return
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            started = true
        }.onFailure {
            AppLogger.w(TAG, "Failed to register Wi-Fi monitor: ${it.message}")
        }
    }

    fun stop() {
        if (!started) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        started = false
    }

    private fun handleNetworkEvent(
        network: Network,
        capabilities: NetworkCapabilities? = null,
    ) {
        val ssid = currentSsid(capabilities) ?: return
        scope.launch { evaluateAutoConnect(ssid) }
    }

    private suspend fun evaluateAutoConnect(rawSsid: String) {
        if (!settings.autoConnectUntrustedWifi.value) return
        if (!hasLocationPermission()) return
        val trusted = settings.trustedWifiSsids.value
        if (rawSsid in trusted) return

        val state = vpnController.state.value.state
        if (state !in ELIGIBLE_STATES) return
        if (!vpnController.hasDisclosureConsent()) return
        if (vpnController.permissionIntent() != null) return

        val now = System.currentTimeMillis()
        val lastAttempt = lastAttemptAtBySsid[rawSsid] ?: 0L
        if (now - lastAttempt < RETRY_INTERVAL_MS) return
        lastAttemptAtBySsid[rawSsid] = now

        AppLogger.i(TAG, "Untrusted Wi-Fi \"$rawSsid\" detected, auto-connecting VPN")
        runCatching { vpnController.connect() }
            .onFailure { error ->
                AppLogger.w(TAG, "Wi-Fi auto-connect failed: ${error.message}")
            }
    }

    private fun currentSsid(capabilities: NetworkCapabilities?): String? {
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (capabilities?.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        } ?: legacySsid()
        return normalizeSsid(ssid)
    }

    @SuppressLint("MissingPermission")
    private fun legacySsid(): String? = runCatching {
        if (!hasLocationPermission()) return null
        wifiManager?.connectionInfo?.ssid
    }.getOrNull()

    private fun normalizeSsid(ssid: String?): String? {
        val clean = ssid?.trim()?.removeSurrounding("\"") ?: return null
        if (clean.isBlank() || clean == UNKNOWN_SSID || clean.startsWith("<")) return null
        return clean
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "WifiAutoConnect"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val RETRY_INTERVAL_MS = 5 * 60_000L
        private val ELIGIBLE_STATES = setOf(
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR,
        )
    }
}
