package com.leviknet.vpn.core.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.leviknet.vpn.core.logger.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Listens for Levik Home routers on the local network for a few seconds.
 * NsdManager works on the physical network even while the VPN is on.
 */
class HomeScanner(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executor { it.run() }

    suspend fun scan(durationMs: Long = SCAN_MS): List<HomeBeacon> {
        val manager = nsd ?: return emptyList()
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLogger.w(TAG, "Home discovery failed: $errorCode")
                found.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            manager.discoverServices(LevikHome.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: IllegalArgumentException) {
            AppLogger.w(TAG, "Home discovery unavailable: ${error.javaClass.simpleName}")
            return emptyList()
        }
        val services = LinkedHashMap<String, NsdServiceInfo>()
        try {
            withTimeoutOrNull(durationMs) {
                for (service in found) {
                    if (services.size < MAX_SERVICES) services.putIfAbsent(service.serviceName, service)
                }
            }
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            found.close()
        }
        // Resolved one at a time: older NsdManager refuses a second resolve while one runs.
        return services.values.mapNotNull { service ->
            withTimeoutOrNull(RESOLVE_MS) { resolve(manager, service) }?.let { LevikHome.parseBeacon(it.attributes) }
        }.distinctBy { it.homeId }
    }

    private suspend fun resolve(manager: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveWithCallback(manager, service)
        else resolveLegacy(manager, service)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveWithCallback(manager: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(serviceInfo)
                    runCatching { manager.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            try {
                manager.registerServiceInfoCallback(service, executor, callback)
            } catch (error: IllegalArgumentException) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { runCatching { manager.unregisterServiceInfoCallback(callback) } }
        }

    // resolveService is the only way to read TXT attributes before Android 14.
    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(manager: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(serviceInfo)
                }
            }
            try {
                manager.resolveService(service, listener)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                continuation.resume(null)
            }
        }

    private companion object {
        const val TAG = "LevikHome"
        const val SCAN_MS = 4_000L
        const val RESOLVE_MS = 3_000L
        const val MAX_SERVICES = 8
    }
}
