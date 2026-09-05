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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Independent anonymous transport: never MobileApiClient, accounts, signing or disk queues. */
class WhitelistMapReporter(context: Context, private val settings: AppSettings) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false }
    private var cached: RegionToken? = null

    private data class RegionToken(
        val network: Network,
        val token: String,
        val receivedElapsedMs: Long,
        val expiresElapsedMs: Long,
    )

    @Serializable
    private data class RegionResponse(val token: String, val expiresAt: Long)

    @Serializable
    private data class Report(val token: String, val signal: String)

    suspend fun clear() = mutex.withLock { cached = null }

    suspend fun collect(detector: WhitelistDetector) = mutex.withLock {
        if (!settings.whitelistMapEnabled.value) { cached = null; return@withLock }
        withContext(Dispatchers.IO) {
            try {
                val network = detector.directNetwork() ?: run { cached = null; return@withContext }
                if (!isPhysicalNetwork(network)) { cached = null; return@withContext }
                val now = SystemClock.elapsedRealtime()
                cached = cached?.takeIf {
                    whitelistRegionTokenIsUsable(it.network == network, it.receivedElapsedMs, it.expiresElapsedMs, now)
                }
                // Region is always obtained through exactly the physical network being measured.
                // A currently valid in-memory token can survive a temporary block of this endpoint.
                if (cached == null) {
                    val response = request("region", network, null) ?: return@withContext
                    val region = json.decodeFromString<RegionResponse>(response)
                    val ttl = region.expiresAt - System.currentTimeMillis()
                    if (region.token.length !in 1..512 || ttl !in 1..WHITELIST_MAP_MAX_TOKEN_AGE_MS) return@withContext
                    val received = SystemClock.elapsedRealtime()
                    cached = RegionToken(network, region.token, received, received + ttl)
                }
                val region = cached ?: return@withContext
                val mode = detector.detect(network, forceRefresh = true)
                val signal = whitelistMapSignal(mode) ?: return@withContext
                currentCoroutineContext().ensureActive()
                if (!settings.whitelistMapEnabled.value || detector.directNetwork() != network ||
                    !isPhysicalNetwork(network) || !whitelistRegionTokenIsUsable(
                        region.network == network, region.receivedElapsedMs, region.expiresElapsedMs, SystemClock.elapsedRealtime(),
                    )
                ) return@withContext
                // Default route may be VPN. Geography comes from the signed physical-network token.
                // No replay later: the report is discarded on failure, not queued as a fresh sample.
                request("report", null, json.encodeToString(Report(region.token, signal)))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Deliberately no request/response, token, IP or exception logging.
            }
        }
    }

    private fun isPhysicalNetwork(network: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private suspend fun request(path: String, network: Network?, body: String?): String? {
        currentCoroutineContext().ensureActive()
        if (!settings.whitelistMapEnabled.value) return null
        val url = URL("https://leviknet.com/api/whitelist/$path")
        val connection = (network?.openConnection(url, Proxy.NO_PROXY) ?: url.openConnection(Proxy.NO_PROXY)) as HttpURLConnection
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
            if (!settings.whitelistMapEnabled.value) return null
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 422) cached = null
            if (status != 200) return null
            return connection.inputStream.use { input ->
                val bytes = ByteArray(1025)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > 1024) null else String(bytes, 0, size, Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
}
