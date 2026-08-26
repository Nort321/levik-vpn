package com.leviknet.vpn.core.network

import com.leviknet.vpn.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MobileApiClient(
    baseUrl: String,
    private val signer: RequestSigner,
    private val attestationProvider: AppAttestationProvider,
    private val requireAttestation: Boolean,
    private val json: Json,
) {
    private val origin = URL(baseUrl).also { parsed ->
        require(parsed.protocol == "https") { "Mobile API requires HTTPS" }
        require(parsed.host.isNotBlank()) { "Mobile API host is required" }
        require(parsed.path.isEmpty() || parsed.path == "/") {
            "Mobile API base URL must not contain a path"
        }
    }

    suspend fun createChallenge(request: AuthChallengeRequest): AuthChallengeResponse {
        val response = post<AuthChallengeRequest, AuthChallengeResponse>(
            path = AUTH_CHALLENGE_PATH,
            request = request,
            accessToken = null,
            requiresIntegrity = true,
        )
        checkSuccess(response.ok)
        return response
    }

    suspend fun pollStatus(request: AuthStatusRequest): AuthStatusResponse {
        val response = post<AuthStatusRequest, AuthStatusResponse>(
            path = AUTH_STATUS_PATH,
            request = request,
            accessToken = null,
            requiresIntegrity = false,
        )
        checkSuccess(response.ok)
        return response
    }

    suspend fun activateDeviceTrial(
        request: DeviceTrialActivationRequest,
    ): TrialActivationResponse {
        val response = post<DeviceTrialActivationRequest, TrialActivationResponse>(
            path = TRIAL_ACTIVATE_PATH,
            request = request,
            accessToken = null,
            requiresIntegrity = true,
        )
        checkSuccess(response.ok)
        return response
    }

    suspend fun activateMobileTrial(accessToken: String): TrialActivationResponse {
        val response = post<MobileTrialActivationRequest, TrialActivationResponse>(
            path = TRIAL_ACTIVATE_PATH,
            request = MobileTrialActivationRequest(),
            accessToken = accessToken,
            requiresIntegrity = true,
        )
        checkSuccess(response.ok)
        return response
    }

    suspend fun account(accessToken: String): MobileAccountResponse {
        val response = request<MobileAccountResponse>(
            method = METHOD_GET,
            path = ACCOUNT_PATH,
            body = EMPTY_BODY,
            accessToken = accessToken,
            requiresIntegrity = false,
        )
        checkSuccess(response.ok)
        return response
    }

    suspend fun catalog(accessToken: String): CatalogResponse {
        val response = request<CatalogResponse>(
            method = METHOD_GET,
            path = CATALOG_PATH,
            body = EMPTY_BODY,
            accessToken = accessToken,
            requiresIntegrity = false,
        )
        checkSuccess(response.ok)
        return response
    }

    suspend fun createOrder(
        accessToken: String,
        request: CreateOrderRequest,
    ): CreateOrderResponse {
        val response = post<CreateOrderRequest, CreateOrderResponse>(
            path = ORDER_CREATE_PATH,
            request = request,
            accessToken = accessToken,
            requiresIntegrity = true,
        )
        checkSuccess(response.ok)
        return response
    }

    suspend fun tunnelProfile(
        accessToken: String,
        subscriptionId: String,
    ): TunnelProfileEnvelope {
        val response = post<TunnelProfileRequest, TunnelProfileResponse>(
            path = TUNNEL_PROFILE_PATH,
            request = TunnelProfileRequest(subscriptionId),
            accessToken = accessToken,
            requiresIntegrity = true,
        )
        checkSuccess(response.ok)
        return response.profile
    }

    suspend fun logout(accessToken: String) {
        val response = request<LogoutResponse>(
            method = METHOD_POST,
            path = AUTH_LOGOUT_PATH,
            body = EMPTY_JSON_BODY,
            accessToken = accessToken,
            requiresIntegrity = false,
        )
        checkSuccess(response.ok)
    }

    suspend fun revokeDevice(
        accessToken: String,
        subscriptionId: String,
        deviceId: String,
    ) {
        val response = post<RevokeDeviceRequest, SimpleSuccessResponse>(
            path = REVOKE_DEVICE_PATH,
            request = RevokeDeviceRequest(subscriptionId, deviceId),
            accessToken = accessToken,
            requiresIntegrity = false,
        )
        checkSuccess(response.ok)
    }

    suspend fun setSubscriptionShield(
        accessToken: String,
        subscriptionId: String,
        enabled: Boolean,
    ): Boolean {
        val response = post<ShieldUpdateRequest, ShieldUpdateResponse>(
            path = SUBSCRIPTION_SHIELD_PATH,
            request = ShieldUpdateRequest(subscriptionId, enabled),
            accessToken = accessToken,
            requiresIntegrity = false,
        )
        checkSuccess(response.ok)
        return response.enabled
    }

    suspend fun checkIp(): IpCheckResponse {
        return request<IpCheckResponse>(
            method = METHOD_GET,
            path = CHECK_IP_PATH,
            body = EMPTY_BODY,
            accessToken = null,
            requiresIntegrity = false,
        )
    }

    suspend fun status(): LevikStatusSnapshot = request(
        method = METHOD_GET,
        path = STATUS_PATH,
        body = EMPTY_BODY,
        accessToken = null,
        requiresIntegrity = false,
    )

    suspend fun freeProxy(): FreeProxyResponse = request(
        method = METHOD_POST,
        path = FREE_PROXY_PATH,
        body = EMPTY_JSON_BODY,
        accessToken = null,
        requiresIntegrity = false,
    )

    suspend fun createSupportNote(noteRequest: CreateNoteRequest): CreateNoteResponse {
        return post<CreateNoteRequest, CreateNoteResponse>(
            path = NOTES_PATH,
            request = noteRequest,
            accessToken = null,
            requiresIntegrity = false,
        )
    }

    suspend fun reportCensorshipTelemetry(telemetry: BrowserCheckReportRequest) {
        runCatching {
            post<BrowserCheckReportRequest, ApiFailureResponse>(
                path = BROWSER_CHECKS_PATH,
                request = telemetry,
                accessToken = null,
                requiresIntegrity = false,
            )
        }
    }

    private inline suspend fun <reified Request : Any, reified Response : Any> post(
        path: String,
        request: Request,
        accessToken: String?,
        requiresIntegrity: Boolean,
    ): Response = request(
        method = METHOD_POST,
        path = path,
        body = json.encodeToString(request).encodeToByteArray(),
        accessToken = accessToken,
        requiresIntegrity = requiresIntegrity,
    )

    private suspend inline fun <reified Response : Any> request(
        method: String,
        path: String,
        body: ByteArray,
        accessToken: String?,
        requiresIntegrity: Boolean,
    ): Response = withContext(Dispatchers.IO) {
        val url = URL(origin, path)
        require(url.protocol == origin.protocol && url.host == origin.host && url.port == origin.port) {
            "Cross-origin mobile API request is not allowed"
        }
        val signed = signer.sign(
            method = method,
            path = url.path,
            accessToken = accessToken,
            body = body,
        )
        val attestation = if (requiresIntegrity) {
            attestationProvider.token(signed.requestHash)
        } else {
            AttestationResult.Unavailable()
        }
        if (requiresIntegrity && requireAttestation && attestation !is AttestationResult.Available) {
            val cause = (attestation as? AttestationResult.Unavailable)?.cause
            throw ApiException.AttestationUnavailable(cause)
        }

        val connection = try {
            (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", JSON_MEDIA_TYPE)
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("X-Levik-App-Version", BuildConfig.VERSION_NAME)
                setRequestProperty("X-Levik-Device-Id", signed.deviceId)
                setRequestProperty("X-Levik-Timestamp", signed.timestamp.toString())
                setRequestProperty("X-Levik-Nonce", signed.nonce)
                setRequestProperty("X-Levik-Signature", signed.signature)
                if (attestation is AttestationResult.Available) {
                    setRequestProperty("X-Levik-Integrity", attestation.token)
                }
                accessToken?.let { token ->
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (method == METHOD_POST) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    setRequestProperty("Content-Type", JSON_MEDIA_TYPE)
                }
            }
        } catch (error: IOException) {
            throw ApiException.Network(error)
        }

        try {
            if (method == METHOD_POST) {
                connection.outputStream.use { output ->
                    output.write(body)
                    output.flush()
                }
            }

            val status = connection.responseCode
            if (status in 300..399) {
                throw ApiException.InvalidResponse("Mobile API redirects are not allowed (HTTP $status)")
            }

            val contentType = connection.contentType.orEmpty()
            val isJson = contentType.contains("application/json", ignoreCase = true) ||
                contentType.contains("text/json", ignoreCase = true) ||
                contentType.contains("+json", ignoreCase = true)

            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = readBounded(
                stream,
                connection.contentLengthLong,
            )

            if (status !in 200..299) {
                if (status == HttpsURLConnection.HTTP_UNAUTHORIZED) {
                    throw ApiException.Unauthorized()
                }
                if (isJson && responseBody.isNotEmpty()) {
                    val failure = runCatching {
                        json.decodeFromString<ApiFailureResponse>(responseBody.decodeToString())
                    }.getOrNull()
                    if (failure != null) {
                        throw ApiException.Rejected(
                            code = failure.error.code,
                            retryable = failure.error.retryable,
                            status = status,
                        )
                    }
                }
                val retryable = status in setOf(408, 425, 429, 500, 502, 503, 504)
                throw ApiException.Rejected(
                    code = "http_$status",
                    retryable = retryable,
                    status = status,
                )
            }

            if (!isJson) {
                throw ApiException.InvalidResponse("Mobile API returned unexpected content type: $contentType (HTTP $status)")
            }

            try {
                json.decodeFromString<Response>(responseBody.decodeToString())
            } catch (error: SerializationException) {
                throw ApiException.InvalidResponse("Invalid mobile API response", error)
            } finally {
                responseBody.fill(0)
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ApiException.Network(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream?, contentLength: Long): ByteArray {
        if (input == null) return ByteArray(0)
        if (contentLength > MAX_RESPONSE_BYTES) {
            throw ApiException.InvalidResponse("Mobile API response is too large")
        }

        return input.use { stream ->
            val output = ByteArrayOutputStream(
                contentLength.coerceIn(0, MAX_RESPONSE_BYTES.toLong()).toInt(),
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw ApiException.InvalidResponse("Mobile API response is too large")
                }
                output.write(buffer, 0, read)
            }
            buffer.fill(0)
            output.toByteArray()
        }
    }

    private fun checkSuccess(ok: Boolean) {
        if (!ok) throw ApiException.InvalidResponse("Mobile API success response was rejected")
    }

    companion object {
        private const val AUTH_CHALLENGE_PATH = "/api/mobile/v1/auth/challenge"
        private const val AUTH_STATUS_PATH = "/api/mobile/v1/auth/status"
        private const val AUTH_LOGOUT_PATH = "/api/mobile/v1/auth/logout"
        private const val TRIAL_ACTIVATE_PATH = "/api/mobile/v1/trial/activate"
        private const val REVOKE_DEVICE_PATH = "/api/mobile/v1/devices/revoke"
        private const val SUBSCRIPTION_SHIELD_PATH = "/api/mobile/v1/subscriptions/shield"
        private const val ACCOUNT_PATH = "/api/mobile/v1/account"
        private const val CATALOG_PATH = "/api/mobile/v1/catalog"
        private const val ORDER_CREATE_PATH = "/api/mobile/v1/orders/create"
        private const val TUNNEL_PROFILE_PATH = "/api/mobile/v1/tunnel-profile"
        private const val CHECK_IP_PATH = "/api/check"
        private const val STATUS_PATH = "/api/status"
        private const val FREE_PROXY_PATH = "/api/free-proxy"
        private const val NOTES_PATH = "/api/notes"
        private const val BROWSER_CHECKS_PATH = "/api/monitor/v1/browser-checks"
        private const val METHOD_GET = "GET"
        private const val METHOD_POST = "POST"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private val EMPTY_BODY = ByteArray(0)
        private val EMPTY_JSON_BODY = "{}".toByteArray(StandardCharsets.UTF_8)
        private val USER_AGENT =
            "LevikVPN-Android/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
    }
}
