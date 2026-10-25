package com.leviknet.vpn.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.data.AppRepository
import com.leviknet.vpn.data.AppSettings
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Levik Home on the phone, docs/app-platform.md "Levik Home". While the VPN
 * runs (or the app is open) it listens for the account's home router and
 * tells the server, so the phone takes no slot; when the router's VPN
 * protects the home, the phone's VPN sleeps until the phone leaves.
 */
class HomeWatcher(
    context: Context,
    private val settings: AppSettings,
    private val repository: AppRepository,
    private val vpnController: VpnController,
    private val directNetwork: () -> Network?,
    private val foreground: StateFlow<Boolean>,
) {
    private enum class Mode { IDLE, ONCE, WATCH }

    private val scanner = HomeScanner(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val policy = HomeSleepPolicy()
    private val answers = HashMap<String, Pair<Long, VerifiedHome?>>()
    private var sleptOn: Network? = null
    private var waking = false
    private var lastOnceScanMs = 0L

    suspend fun run() = coroutineScope {
        launch { followVpn() }
        combine(
            repository.session,
            vpnController.state.map { it.state }.distinctUntilChanged(),
            foreground,
        ) { session, state, visible ->
            when {
                session != SessionStatus.Authenticated -> Mode.IDLE
                state == VpnConnectionState.CONNECTED || state == VpnConnectionState.PAUSED && policy.asleep -> Mode.WATCH
                visible -> Mode.ONCE
                else -> Mode.IDLE
            }
        }.distinctUntilChanged().collectLatest { mode ->
            when (mode) {
                Mode.WATCH -> watch()
                Mode.ONCE -> scanOnce()
                Mode.IDLE -> if (repository.session.value != SessionStatus.Authenticated) apply(policy.left())
            }
        }
    }

    /** Tells a wake-up by leaving from the user's own resume, and notices the VPN being turned off. */
    private suspend fun followVpn() {
        var previous = vpnController.state.value.state
        vpnController.state.map { it.state }.distinctUntilChanged().collect { state ->
            if (previous == VpnConnectionState.PAUSED && state != VpnConnectionState.PAUSED) {
                when {
                    waking -> waking = false
                    state == VpnConnectionState.DISCONNECTED || state == VpnConnectionState.STOPPING -> policy.stopped()
                    else -> policy.resumedByUser()
                }
            }
            previous = state
        }
    }

    private suspend fun watch() = coroutineScope {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val listener = launch { physicalNetworkChanges().collect { changes.trySend(Unit) } }
        try {
            var first = true
            while (true) {
                val network = directNetwork()
                when {
                    policy.asleep && network != sleptOn -> apply(policy.left())
                    network == null || !isLocal(network) -> apply(policy.silence(System.currentTimeMillis()))
                    else -> {
                        val home = listen()
                        apply(
                            policy.heard(
                                home,
                                System.currentTimeMillis(),
                                vpnConnected = vpnController.state.value.state == VpnConnectionState.CONNECTED,
                                enabled = settings.sleepAtHome.value && !repository.requiresLocalServiceRoutes(),
                            ),
                        )
                    }
                }
                // Registering the callback reports the networks already there; that is not a change.
                if (first) changes.tryReceive()
                first = false
                withTimeoutOrNull(if (policy.asleep) ASLEEP_TICK_MS else AWAKE_TICK_MS) { changes.receive() }
            }
        } finally {
            listener.cancel()
        }
    }

    private suspend fun scanOnce() {
        val now = System.currentTimeMillis()
        if (now - lastOnceScanMs < ONCE_INTERVAL_MS) return
        val network = directNetwork() ?: return
        if (!isLocal(network)) return
        lastOnceScanMs = now
        listen()
    }

    /** The account's home heard on this network, preferring one whose router protects it. */
    private suspend fun listen(): VerifiedHome? {
        val now = System.currentTimeMillis()
        val homes = scanner.scan().filter { LevikHome.fresh(it, now) }.mapNotNull { beacon -> verify(beacon) }
        return homes.firstOrNull { it.protects } ?: homes.firstOrNull()
    }

    /** One report per home and window: the server's answer does not change within it. */
    private suspend fun verify(beacon: HomeBeacon): VerifiedHome? {
        answers[beacon.homeId]?.let { (window, answer) -> if (window == beacon.window) return answer }
        val answer = try {
            LevikHome.parseSighting(repository.reportHomeSighting(beacon.homeId, beacon.window, beacon.proof))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            AppLogger.w(TAG, "Home sighting failed: ${error.javaClass.simpleName}")
            return null
        }
        if (answers.size >= MAX_ANSWERS) answers.clear()
        answers[beacon.homeId] = beacon.window to answer
        return answer
    }

    private fun apply(action: HomeSleepPolicy.Action) {
        when (action) {
            HomeSleepPolicy.Action.None -> Unit
            is HomeSleepPolicy.Action.Sleep -> {
                sleptOn = directNetwork()
                AppLogger.i(TAG, "At home with the router's VPN on: the VPN sleeps")
                vpnController.pauseAtHome(action.home)
            }
            HomeSleepPolicy.Action.Wake -> {
                sleptOn = null
                if (vpnController.state.value.state == VpnConnectionState.PAUSED) {
                    AppLogger.i(TAG, "Left home: the VPN wakes up")
                    waking = true
                    vpnController.resume()
                }
            }
        }
    }

    private fun isLocal(network: Network): Boolean {
        val capabilities = connectivity?.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Wi-Fi or mobile data coming and going under the VPN. */
    private fun physicalNetworkChanges(): Flow<Unit> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            awaitClose()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
            override fun onLost(network: Network) {
                trySend(Unit)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
        } catch (error: RuntimeException) {
            AppLogger.w(TAG, "Network callback unavailable: ${error.javaClass.simpleName}")
            awaitClose()
            return@callbackFlow
        }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    private companion object {
        const val TAG = "LevikHome"
        const val AWAKE_TICK_MS = 2 * 60_000L
        const val ASLEEP_TICK_MS = 60_000L
        const val ONCE_INTERVAL_MS = 2 * 60_000L
        const val MAX_ANSWERS = 16
    }
}
