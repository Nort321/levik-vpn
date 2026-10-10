package com.leviknet.vpn.core.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val TELEMETRY_ORIGINS = listOf("https://leviknet.org", "https://leviknet.com")
private const val MAX_TOKEN_RESPONSE_BYTES = 1_024

/**
 * Anonymous requests: no cookies, credentials, signatures or attestation, so a
 * report cannot be tied to an account. Independent of MobileApiClient.
 */
class HttpTelemetryTransport : TelemetryTransport {
    override suspend fun send(body: String): SendOutcome = withContext(Dispatchers.IO) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        for (origin in TELEMETRY_ORIGINS) {
            val status = runCatching {
                request(URL("$origin/api/telemetry/v1/sessions"), null, bytes, 15_000) { it.responseCode }
            }.getOrNull() ?: continue
            when {
                status == 202 || status in 200..299 -> return@withContext SendOutcome.SENT
                status == 400 || status == 413 || status == 415 -> return@withContext SendOutcome.REJECTED
                status == 429 -> return@withContext SendOutcome.RETRY
            }
        }
        SendOutcome.RETRY
    }

    /**
     * Asks which operator [network] belongs to. The request is bound to that
     * physical network; through the tunnel the server answers with null.
     */
    suspend fun networkToken(network: Network): NetworkToken? = withContext(Dispatchers.IO) {
        for (origin in TELEMETRY_ORIGINS) {
            val text = runCatching {
                request(URL("$origin/api/telemetry/v1/network"), network, null, 4_000) { connection ->
                    if (connection.responseCode != 200) return@request null
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(MAX_TOKEN_RESPONSE_BYTES + 1)
                        var size = 0
                        while (size < buffer.size) {
                            val count = input.read(buffer, size, buffer.size - size)
                            if (count < 0) break
                            size += count
                        }
                        if (size > MAX_TOKEN_RESPONSE_BYTES) null else String(buffer, 0, size, Charsets.UTF_8)
                    }
                }
            }.getOrNull() ?: continue
            return@withContext parseNetworkToken(text)
        }
        null
    }

    private fun <T> request(
        url: URL,
        network: Network?,
        body: ByteArray?,
        timeoutMs: Int,
        read: (HttpURLConnection) -> T,
    ): T {
        val connection = (network?.openConnection(url, Proxy.NO_PROXY) ?: url.openConnection(Proxy.NO_PROXY))
            as HttpURLConnection
        try {
            connection.apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "Levik-Connection-Quality/1")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Connection", "close")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) connection.outputStream.use { it.write(body) }
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseNetworkToken(text: String): NetworkToken? = runCatching {
    val value = Json.parseToJsonElement(text).jsonObject
    val token = (value["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val expiresAt = (value["expiresAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (token.isEmpty() || token.length > 400) return null
    NetworkToken(token, Instant.parse(expiresAt).toEpochMilli())
}.getOrNull()

fun androidTelemetryClient(versionName: String): TelemetryClientInfo =
    telemetryClientInfo(versionName, Build.VERSION.RELEASE.orEmpty(), Build.MANUFACTURER.orEmpty())

fun telemetryNetworkType(capabilities: NetworkCapabilities?): NetworkType = when {
    capabilities == null -> NetworkType.UNKNOWN
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
    else -> NetworkType.OTHER
}

fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/**
 * Resolves sessions left by a dead process from the reasons Android recorded
 * (API 30+). Older systems report the end as unknown.
 */
fun processExitResolver(context: Context): (QueuedSession) -> InterruptedSessionEnd {
    // Read on first use, from the background scope that finalizes queued sessions.
    val exits: List<Pair<Long, SessionEnd>> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                    .orEmpty()
                    .filter { it.processName == context.packageName }
                    .map { it.timestamp to exitReasonEnd(it.reason) }
                    .sortedBy { it.first }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    return { entry ->
        // The first recorded death after the last checkpoint ended that session.
        exits.firstOrNull { (timestamp, _) -> timestamp >= entry.savedAt }
            ?.let { (timestamp, end) -> InterruptedSessionEnd(end, timestamp) }
            ?: InterruptedSessionEnd(SessionEnd(EndBy.UNKNOWN, null), null)
    }
}

/** ApplicationExitInfo reason codes; values are compile-time constants. */
fun exitReasonEnd(reason: Int): SessionEnd = when (reason) {
    ApplicationExitInfo.REASON_LOW_MEMORY -> SessionEnd(EndBy.OS_KILLED, "low_memory")
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> SessionEnd(EndBy.OS_KILLED, "excessive_resource")
    ApplicationExitInfo.REASON_FREEZER -> SessionEnd(EndBy.OS_KILLED, "freezer")
    ApplicationExitInfo.REASON_CRASH_NATIVE -> SessionEnd(EndBy.OS_KILLED, "crash_native")
    ApplicationExitInfo.REASON_CRASH -> SessionEnd(EndBy.OS_KILLED, "crash")
    ApplicationExitInfo.REASON_ANR -> SessionEnd(EndBy.OS_KILLED, "anr")
    ApplicationExitInfo.REASON_SIGNALED -> SessionEnd(EndBy.OS_KILLED, "signaled")
    ApplicationExitInfo.REASON_USER_REQUESTED -> SessionEnd(EndBy.OS_KILLED, "user_force_stop")
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> SessionEnd(EndBy.SYSTEM, "app_update")
    ApplicationExitInfo.REASON_USER_STOPPED -> SessionEnd(EndBy.SYSTEM, null)
    ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_EXIT_SELF -> SessionEnd(EndBy.UNKNOWN, null)
    else -> SessionEnd(EndBy.OS_KILLED, "unknown")
}
