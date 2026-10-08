package com.leviknet.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

internal fun selectYandexGuestNetwork(context: Context): Network? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val eligible = manager.allNetworks.filter {
        manager.getNetworkCapabilities(it)?.let { caps ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
    }
    return eligible.firstOrNull {
        manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } ?: eligible.firstOrNull()
}
