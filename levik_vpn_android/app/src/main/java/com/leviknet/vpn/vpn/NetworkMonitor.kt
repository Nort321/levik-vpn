package com.leviknet.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class NetworkMonitor(
    context: Context,
    private val handleAvailable: (Network) -> Unit,
    private val handleLost: (Network) -> Unit,
) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities.isUsableUnderlyingNetwork()) {
                handleAvailable(network)
            }
        }

        override fun onLost(network: Network) {
            handleLost(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (networkCapabilities.isUsableUnderlyingNetwork()) {
                handleAvailable(network)
            }
        }
    }

    fun activeNetwork(): Network? {
        val candidates = connectivityManager.allNetworks.filter { network ->
            connectivityManager.getNetworkCapabilities(network).isUsableUnderlyingNetwork()
        }
        return candidates.maxByOrNull(::preference)
    }

    fun preference(network: Network): Int {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return -1
        val validationScore = if (
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            10
        } else {
            0
        }
        return validationScore + when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
    }

    fun start() {
        if (started) return
        connectivityManager.registerNetworkCallback(request, callback)
        started = true
    }

    fun stop() {
        if (!started) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        started = false
    }

    private fun NetworkCapabilities?.isUsableUnderlyingNetwork(): Boolean =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}
