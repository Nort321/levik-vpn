package com.leviknet.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class NetworkMonitor(
    context: Context,
    private val handleAvailable: (Network) -> Unit,
    private val handleLost: (Network) -> Unit,
) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val cellularRequestLock = Any()
    private var started = false
    private var cellularRequestCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularRequestNetwork: Network? = null
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

    fun activeNetwork(
        requirement: TunnelNetworkRequirement = TunnelNetworkRequirement.ANY,
    ): Network? {
        val candidates = connectivityManager.allNetworks.filter { network ->
            connectivityManager.getNetworkCapabilities(network).isUsableUnderlyingNetwork() &&
                isCompatible(network, requirement)
        }
        return candidates.maxByOrNull(::preference)
    }

    suspend fun acquireNetwork(requirement: TunnelNetworkRequirement): Network? =
        if (requiresDedicatedCellularRequest(requirement)) {
            acquireCellularNetwork()
        } else {
            activeNetwork(requirement)
        }

    /**
     * Actively requests and retains cellular even while Wi-Fi is the default network. The callback
     * remains registered for the relay session and is released by [releaseCellularNetwork].
     */
    private suspend fun acquireCellularNetwork(): Network? = suspendCancellableCoroutine { continuation ->
        releaseCellularNetwork()
        val completed = AtomicBoolean(false)
        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isCurrentCellularCallback(this)) return
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (!capabilities.isUsableUnderlyingNetwork() || !isCellular(network)) return
                synchronized(cellularRequestLock) {
                    cellularRequestNetwork = network
                }
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(network)
                } else {
                    handleAvailable(network)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (!isCurrentCellularCallback(this)) return
                if (networkCapabilities.isUsableUnderlyingNetwork() &&
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                ) {
                    synchronized(cellularRequestLock) {
                        cellularRequestNetwork = network
                    }
                    handleAvailable(network)
                }
            }

            override fun onLost(network: Network) {
                if (!isCurrentCellularCallback(this)) return
                synchronized(cellularRequestLock) {
                    if (cellularRequestNetwork == network) cellularRequestNetwork = null
                }
                handleLost(network)
            }

            override fun onUnavailable() {
                if (!isCurrentCellularCallback(this)) return
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(null)
                }
                releaseCellularNetwork(this)
            }
        }
        synchronized(cellularRequestLock) {
            cellularRequestCallback = cellularCallback
        }
        continuation.invokeOnCancellation {
            if (!completed.get()) releaseCellularNetwork(cellularCallback)
        }
        runCatching {
            connectivityManager.requestNetwork(
                cellularRequest,
                cellularCallback,
                CELLULAR_REQUEST_TIMEOUT_MS,
            )
        }.onFailure {
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(null)
            }
            releaseCellularNetwork(cellularCallback)
        }
    }

    fun releaseCellularNetwork() {
        releaseCellularNetwork(expectedCallback = null)
    }

    private fun releaseCellularNetwork(
        expectedCallback: ConnectivityManager.NetworkCallback?,
    ) {
        val callback = synchronized(cellularRequestLock) {
            if (expectedCallback != null && cellularRequestCallback !== expectedCallback) {
                return
            }
            cellularRequestNetwork = null
            cellularRequestCallback.also { cellularRequestCallback = null }
        }
        callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
    }

    private fun isCurrentCellularCallback(
        callback: ConnectivityManager.NetworkCallback,
    ): Boolean = synchronized(cellularRequestLock) {
        cellularRequestCallback === callback
    }

    fun isCompatible(network: Network, requirement: TunnelNetworkRequirement): Boolean =
        when (requirement) {
            TunnelNetworkRequirement.ANY -> true
            TunnelNetworkRequirement.CELLULAR_ALLOWLIST -> isCellular(network)
        }

    fun isCellular(network: Network): Boolean =
        connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

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

    fun stop(releaseCellular: Boolean = true) {
        if (releaseCellular) releaseCellularNetwork()
        if (started) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            started = false
        }
    }

    private fun NetworkCapabilities?.isUsableUnderlyingNetwork(): Boolean =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private companion object {
        const val CELLULAR_REQUEST_TIMEOUT_MS = 15_000
    }
}

internal fun requiresDedicatedCellularRequest(requirement: TunnelNetworkRequirement): Boolean =
    requirement == TunnelNetworkRequirement.CELLULAR_ALLOWLIST
