package com.leviknet.vpn.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.leviknet.vpn.data.AppSettings
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Independent anonymous transport: never MobileApiClient, accounts, signing or disk queues. */
class WhitelistMapReporter(context: Context, private val settings: AppSettings) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutex = Mutex()
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestSlots = Semaphore(2)
    private val delivery = WhitelistMapDelivery<Network>(
        enabled = { settings.whitelistMapEnabled.value },
        elapsedMillis = SystemClock::elapsedRealtime,
        wallMillis = System::currentTimeMillis,
        request = ::request,
    )
    private var currentNetworkCheck: () -> Boolean = { false }

    suspend fun clear() = mutex.withLock { delivery.clear() }

    /** [rememberScope] keeps the token's region and operator so the detector uses them between reports. */
    suspend fun collect(
        detector: WhitelistDetector,
        rememberScope: (AllowlistProbeScope) -> Unit = {},
    ) = mutex.withLock {
        if (!settings.whitelistMapEnabled.value) { delivery.clear(); return@withLock }
        withContext(Dispatchers.IO) {
            try {
                val network = detector.directNetwork()?.takeIf(::isCellularNetwork)
                currentNetworkCheck = {
                    network != null && detector.directNetwork() == network && isCellularNetwork(network)
                }
                delivery.collect(network, currentNetworkCheck) { scope ->
                    if (network == null) {
                        WhitelistObservation(WhitelistMode.UNKNOWN, emptyMap())
                    } else {
                        scope?.let(rememberScope)
                        detector.observe(network, scope)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Deliberately no request/response, token, IP or exception logging.
            } finally {
                currentNetworkCheck = { false }
            }
        }
    }

    private fun isCellularNetwork(network: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return whitelistMapNetworkIsEligible(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    private suspend fun request(
        channel: WhitelistMapChannel,
        operation: WhitelistMapOperation,
        network: Network?,
        body: String?,
    ): WhitelistMapResponse? {
        currentCoroutineContext().ensureActive()
        val isCurrent = currentNetworkCheck
        if (!settings.whitelistMapEnabled.value || !isCurrent()) return null
        val url = URL(when (channel) {
            WhitelistMapChannel.WEBSITE -> ServiceEndpoints.webUrl("/api/whitelist/${operation.path}")
            WhitelistMapChannel.SERVICE -> ServiceEndpoints.allowlistApiOrigin() + "/api/whitelist/service/${operation.path}"
        })
        // The service origin comes only from the built-in or signed configuration,
        // never from report data or a subscription.
        if (channel == WhitelistMapChannel.SERVICE && network == null) return null
        val connection = AtomicReference<HttpURLConnection?>(null)
        // Android DNS can outlive socket timeouts and ignore interrupts. A timed-out
        // request is abandoned and cancelled; two slots bound even stuck OS calls.
        val attempt = requestScope.async {
            if (!requestSlots.tryAcquire()) return@async null
            try {
                executeRequest(url, network, operation, body, isCurrent, connection)
            } finally { requestSlots.release() }
        }
        return try { withTimeoutOrNull(8_000) { attempt.await() } }
        finally {
            attempt.cancel()
            connection.get()?.disconnect()
        }
    }

    private suspend fun executeRequest(
        url: URL,
        network: Network?,
        operation: WhitelistMapOperation,
        body: String?,
        isCurrent: () -> Boolean,
        connectionRef: AtomicReference<HttpURLConnection?>,
    ): WhitelistMapResponse? {
        currentCoroutineContext().ensureActive()
        if (!settings.whitelistMapEnabled.value || !isCurrent()) return null
        val connection = (network?.openConnection(url, Proxy.NO_PROXY) ?: url.openConnection(Proxy.NO_PROXY)) as HttpURLConnection
        connectionRef.set(connection)
        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 4_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "Levik-Regional-Observations/1")
                setRequestProperty("Cookie", "")
                setRequestProperty("Authorization", "")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Connection", "close")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                }
            }
            currentCoroutineContext().ensureActive()
            if (!settings.whitelistMapEnabled.value || !isCurrent()) return null
            if (body != null) connection.outputStream.use {
                // Connecting/DNS may have blocked while the worker was cancelled.
                currentCoroutineContext().ensureActive()
                if (!settings.whitelistMapEnabled.value || !isCurrent()) return null
                it.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (status != 200 || operation == WhitelistMapOperation.REPORT) {
                return WhitelistMapResponse(status)
            }
            if (connection.contentLengthLong > 1024) return null
            return connection.inputStream.use { input ->
                val bytes = ByteArray(1025)
                var size = 0
                while (size < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > 1024) null else WhitelistMapResponse(status, String(bytes, 0, size, Charsets.UTF_8))
            }
        } finally { connection.disconnect() }
    }
}
