package com.leviknet.vpn.core.network

import android.os.Build
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.vpn.VpnSnapshot
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

data class DiagnosticServiceCheck(
    val id: String,
    val name: String,
    val targetUrl: String,
    val success: Boolean,
    val latencyMs: Long?,
    val statusCode: Int?,
    val error: String? = null,
)

data class DiagnosticReport(
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val vpnState: String,
    val serverName: String?,
    val ipInfo: IpCheckResponse?,
    val serviceChecks: List<DiagnosticServiceCheck>,
    val timestamp: String,
) {
    fun toFormattedString(): String = buildString {
        appendLine("=== Levik VPN Diagnostic Report ===")
        appendLine("Time: $timestamp")
        appendLine("App: v$appVersion (${BuildConfig.VERSION_CODE})")
        appendLine("OS: $osVersion")
        appendLine("Device: $deviceModel")
        appendLine("VPN Status: $vpnState")
        appendLine("Server: ${serverName ?: "None"}")
        if (ipInfo != null) {
            appendLine("IP: ${ipInfo.address} (${ipInfo.city.orEmpty()}, ${ipInfo.countryCode.orEmpty()})")
            appendLine("ISP: ${ipInfo.provider.orEmpty()} (ASN: ${ipInfo.asn ?: "Unknown"})")
            appendLine("Protected: ${if (ipInfo.isProtected) "YES (LevikVPN Node)" else "NO (Direct)"}")
        } else {
            appendLine("IP: Unknown")
        }
        appendLine("-----------------------------------")
        appendLine("Service Connectivity:")
        for (check in serviceChecks) {
            val status = if (check.success) "OK (${check.latencyMs}ms)" else "FAIL (${check.error ?: "HTTP ${check.statusCode}"})"
            appendLine("- ${check.name}: $status")
        }
        appendLine("===================================")
    }
}

object NetworkDiagnostics {
    private val TARGET_SERVICES = listOf(
        Triple("telegram", "Telegram", "https://t.me"),
        Triple("youtube", "YouTube", "https://www.youtube.com/generate_204"),
        Triple("discord", "Discord", "https://discord.com"),
        Triple("chatgpt", "ChatGPT", "https://chatgpt.com"),
        Triple("notion", "Notion", "https://www.notion.so"),
        Triple("instagram", "Instagram", "https://www.instagram.com"),
        Triple("google", "Google", "https://www.google.com/generate_204"),
        Triple("yandex", "Yandex", "https://ya.ru"),
    )

    suspend fun runDiagnostics(
        vpnSnapshot: VpnSnapshot,
        apiClient: MobileApiClient,
        sendTelemetry: Boolean = true,
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val checksDeferred = async {
            supervisorScope {
                TARGET_SERVICES.map { (id, name, urlStr) ->
                    async { checkService(id, name, urlStr) }
                }.awaitAll()
            }
        }

        val ipInfoDeferred = async {
            runCatching { apiClient.checkIp() }.getOrNull()
        }

        val checks = checksDeferred.await()
        val ipInfo = ipInfoDeferred.await()
        val timestamp = Instant.now().toString()

        val report = DiagnosticReport(
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            vpnState = vpnSnapshot.state.name,
            serverName = vpnSnapshot.serverName,
            ipInfo = ipInfo,
            serviceChecks = checks,
            timestamp = timestamp,
        )

        if (sendTelemetry) {
            runCatching {
                val results = checks.map { check ->
                    BrowserCheckReportServiceResult(
                        serviceSlug = check.id,
                        checks = listOf(
                            BrowserCheckReportEndpointResult(
                                id = "homepage",
                                reachable = check.success,
                                latencyMs = check.latencyMs,
                            ),
                        ),
                    )
                }
                apiClient.reportCensorshipTelemetry(
                    BrowserCheckReportRequest(
                        mode = "diagnostic",
                        measuredAt = timestamp,
                        results = results,
                    ),
                )
            }
        }

        report
    }

    suspend fun createEncryptedSupportNote(
        reportText: String,
        apiClient: MobileApiClient,
    ): String = withContext(Dispatchers.IO) {
        val random = SecureRandom()
        val keyBytes = ByteArray(32).also(random::nextBytes)
        val ivBytes = ByteArray(12).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(reportText.toByteArray(StandardCharsets.UTF_8))

        val keyCommitment = MessageDigest.getInstance("SHA-256").digest(keyBytes)

        val b64u = Base64.getUrlEncoder().withoutPadding()
        val noteId = UUID.randomUUID().toString()

        val response = apiClient.createSupportNote(
            CreateNoteRequest(
                id = noteId,
                keyCommitment = b64u.encodeToString(keyCommitment),
                iv = b64u.encodeToString(ivBytes),
                ciphertext = b64u.encodeToString(ciphertext),
                expiresInDays = 7,
            ),
        )

        if (!response.ok) {
            throw IllegalStateException(response.message ?: "Failed to create secure note")
        }

        "${BuildConfig.CABINET_BASE_URL}/notes/$noteId#${b64u.encodeToString(keyBytes)}"
    }

    private fun checkService(id: String, name: String, targetUrl: String): DiagnosticServiceCheck {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(targetUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; LevikVPN)")
            }
            val code = connection.responseCode
            val elapsed = System.currentTimeMillis() - started
            DiagnosticServiceCheck(
                id = id,
                name = name,
                targetUrl = targetUrl,
                success = code in 200..399,
                latencyMs = elapsed,
                statusCode = code,
            )
        } catch (e: Exception) {
            DiagnosticServiceCheck(
                id = id,
                name = name,
                targetUrl = targetUrl,
                success = false,
                latencyMs = null,
                statusCode = null,
                error = e.message ?: "Connection failed",
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
