package com.leviknet.vpn.data

import android.os.Build
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.AuthChallengeRequest
import com.leviknet.vpn.core.network.AuthChallengeResponse
import com.leviknet.vpn.core.network.AuthStatusRequest
import com.leviknet.vpn.core.network.CatalogResponse
import com.leviknet.vpn.core.network.CreateOrderRequest
import com.leviknet.vpn.core.network.DevicePairingRequest
import com.leviknet.vpn.core.network.DeviceTrialActivationRequest
import com.leviknet.vpn.core.network.OrderSummary
import com.leviknet.vpn.core.network.LoginState
import com.leviknet.vpn.core.network.MobileAccountResponse
import com.leviknet.vpn.core.network.MobileApiClient
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.security.DeviceIdentity
import com.leviknet.vpn.core.security.HybridProfileDecryptor
import com.leviknet.vpn.core.security.SecureFileStore
import com.leviknet.vpn.core.security.TrialDeviceBinding
import com.leviknet.vpn.core.security.validateYandexProfileBinding
import com.leviknet.vpn.vpn.YandexContract
import com.leviknet.vpn.vpn.YandexProviderAuth
import com.leviknet.vpn.vpn.YANDEX_AUTH_REFRESH_LEAD_SECONDS
import com.leviknet.vpn.vpn.PreparedTunnelProfile
import com.leviknet.vpn.vpn.TunnelEngineKind
import com.leviknet.vpn.vpn.TunnelProfileParser
import com.leviknet.vpn.vpn.TunnelProfilePreparer
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.isEligibleForAutomaticSelection
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AppRepository(
    private val apiClient: MobileApiClient,
    private val deviceIdentity: DeviceIdentity,
    private val trialDeviceBinding: TrialDeviceBinding,
    private val secureStore: SecureFileStore,
    private val profileDecryptor: HybridProfileDecryptor,
    private val tunnelProfilePreparer: TunnelProfilePreparer,
    private val supportedTunnelEngines: Set<TunnelEngineKind>,
    private val json: Json,
) {
    private val profileParser = TunnelProfileParser(
        json = json,
        supportedEngines = supportedTunnelEngines,
    )
    private val authMutex = Mutex()
    private val profileMutex = Mutex()
    private val sessionCacheMutex = Mutex()
    private val _session = MutableStateFlow<SessionStatus>(SessionStatus.Loading)
    private val _account = MutableStateFlow<MobileAccountResponse?>(null)
    private val _tunnelProfile = MutableStateFlow<PreparedTunnelProfile?>(null)

    val session: StateFlow<SessionStatus> = _session.asStateFlow()
    val account: StateFlow<MobileAccountResponse?> = _account.asStateFlow()
    val tunnelProfile: StateFlow<PreparedTunnelProfile?> = _tunnelProfile.asStateFlow()

    fun supportsYandex(): Boolean = TunnelEngineKind.LEVIK_YANDEX in supportedTunnelEngines &&
        Build.VERSION.SDK_INT >= 28

    suspend fun savedYandexDocumentUrl(): String? = withContext(Dispatchers.IO) {
        val bytes = secureStore.get(SecureFileStore.YANDEX_DOCUMENT) ?: return@withContext null
        try {
            runCatching { YandexContract.validateDocumentUrl(bytes.decodeToString()) }.getOrNull()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun prepareYandexTunnel(
        subscriptionId: String,
        documentUrl: String,
        providerAuth: YandexProviderAuth,
        selectAfterPreparation: Boolean = true,
    ): PreparedTunnelProfile = profileMutex.withLock {
        require(supportsYandex()) { "Yandex is unavailable in this build" }
        val subscription = _account.value?.subscriptions?.firstOrNull { it.uuid == subscriptionId }
        require(subscription?.capabilities?.yandexRelay == true && subscription.isActiveAt(Instant.now())) {
            "Yandex access is unavailable for this subscription"
        }
        YandexContract.validateDocumentUrl(documentUrl)
        YandexContract.validateProviderAuth(providerAuth)
        val token = requireToken()
        val expectedDeviceId = withContext(Dispatchers.IO) { deviceIdentity.deviceId() }
        val envelope = try {
            apiClient.yandexTunnelProfile(token, subscriptionId, documentUrl, providerAuth)
        } catch (error: ApiException.Unauthorized) {
            clearAuthentication(expectedToken = token)
            throw error
        }
        val plaintext = withContext(Dispatchers.IO) { profileDecryptor.decrypt(envelope) }
        val yandex = try {
            withContext(Dispatchers.Default) {
                val profile = profileParser.parse(plaintext, subscriptionId, expectedDeviceId)
                require(profile.version == 3 && profile.engine == TunnelEngineKind.LEVIK_YANDEX)
                val bootstrap = requireNotNull(profile.yandexBootstrap)
                require(bootstrap.documentUrl == documentUrl &&
                    bootstrap.providerAuth.token == providerAuth.token &&
                    bootstrap.providerAuth.balancerUrl.trimEnd('/') == providerAuth.balancerUrl.trimEnd('/')
                ) { "Yandex document authorization does not match the request" }
                validateYandexProfileBinding(envelope.aad, profile, expectedDeviceId)
                tunnelProfilePreparer.prepare(profile)
            }
        } finally {
            plaintext.fill(0)
        }
        sessionCacheMutex.withLock {
            requireCurrentSession(token)
            val currentSubscription = _account.value?.subscriptions?.firstOrNull { it.uuid == subscriptionId }
            require(currentSubscription?.capabilities?.yandexRelay == true && currentSubscription.isActiveAt(Instant.now())) {
                "Yandex access is unavailable for this subscription"
            }
            val cached = _tunnelProfile.value ?: withContext(Dispatchers.IO) { cachedTunnelBlocking() }
            val prepared = mergeYandexProfile(cached?.takeIf { it.subscriptionId == subscriptionId }, yandex)
            persistPreparedTunnel(prepared)
            withContext(Dispatchers.IO) {
                val encoded = documentUrl.encodeToByteArray()
                try { secureStore.put(SecureFileStore.YANDEX_DOCUMENT, encoded) } finally { encoded.fill(0) }
            }
            if (selectAfterPreparation) selectServer("yandex:document")
            _tunnelProfile.value = prepared
            prepared
        }
    }

    suspend fun initialize() {
        retryPendingRevocation()
        val token = readToken()
        if (token == null) {
            _session.value = SessionStatus.SignedOut
            return
        }
        _session.value = SessionStatus.Authenticated
        runCatching { refreshAccount() }
            .onFailure { error ->
                if (error is ApiException.Unauthorized) {
                    clearAuthentication(expectedToken = token)
                }
            }
    }

    suspend fun hasAppDataDisclosureConsent(): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            secureStore.get(SecureFileStore.APP_DATA_DISCLOSURE_CONSENT)
        }.getOrNull() ?: return@withContext false
        try {
            bytes.contentEquals(APP_DATA_DISCLOSURE_CONSENT_VALUE)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun acceptAppDataDisclosure() = withContext(Dispatchers.IO) {
        secureStore.put(
            SecureFileStore.APP_DATA_DISCLOSURE_CONSENT,
            APP_DATA_DISCLOSURE_CONSENT_VALUE,
        )
    }

    suspend fun beginLogin(accountActivationSupported: Boolean): AuthChallengeResponse =
        authMutex.withLock {
            val request = withContext(Dispatchers.IO) {
                AuthChallengeRequest(
                    accountActivationSupported = accountActivationSupported,
                    publicKeySpki = deviceIdentity.publicKeySpkiBase64Url(),
                    deviceLabel = deviceLabel(),
                    deviceModel = Build.MODEL.sanitized(MAX_DEVICE_FIELD_LENGTH),
                    deviceOs = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                        .sanitized(MAX_DEVICE_FIELD_LENGTH),
                    appVersion = BuildConfig.VERSION_NAME,
                    requestSigningAlgorithm = deviceIdentity.requestSigningAlgorithm(),
                    profileEncryptionAlgorithm = deviceIdentity.profileEncryptionAlgorithm(),
                )
            }
            apiClient.createChallenge(request)
        }

    suspend fun claimDevicePairing(pairingToken: String): Unit = authMutex.withLock {
        require(pairingToken.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid pairing token" }
        if (readToken() != null) {
            throw ApiException.Rejected("pairing_requires_sign_out", false, 409)
        }
        val request = withContext(Dispatchers.IO) {
            DevicePairingRequest(
                pairingToken = pairingToken,
                publicKeySpki = deviceIdentity.publicKeySpkiBase64Url(),
                deviceLabel = deviceLabel(),
                deviceModel = Build.MODEL.sanitized(MAX_DEVICE_FIELD_LENGTH),
                deviceOs = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    .sanitized(MAX_DEVICE_FIELD_LENGTH),
                appVersion = BuildConfig.VERSION_NAME,
                requestSigningAlgorithm = deviceIdentity.requestSigningAlgorithm(),
                profileEncryptionAlgorithm = deviceIdentity.profileEncryptionAlgorithm(),
            )
        }
        val response = apiClient.claimDevicePairing(request)
        val accessToken = requireNotNull(response.accessToken)
        require(accessToken.length in 32..MAX_ACCESS_TOKEN_LENGTH) { "Invalid access token" }
        writeSessionToken(accessToken)
    }

    suspend fun activateDeviceTrial(): MobileAccountResponse = authMutex.withLock {
        check(readToken() == null) { "A signed-in session cannot be replaced by a device trial" }
        val request = withContext(Dispatchers.IO) {
            DeviceTrialActivationRequest(
                trialBinding = trialDeviceBinding.value(),
                publicKeySpki = deviceIdentity.publicKeySpkiBase64Url(),
                deviceLabel = deviceLabel(),
                deviceModel = Build.MODEL.sanitized(MAX_DEVICE_FIELD_LENGTH),
                deviceOs = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    .sanitized(MAX_DEVICE_FIELD_LENGTH),
                appVersion = BuildConfig.VERSION_NAME,
                requestSigningAlgorithm = deviceIdentity.requestSigningAlgorithm(),
                profileEncryptionAlgorithm = deviceIdentity.profileEncryptionAlgorithm(),
            )
        }
        val response = apiClient.activateDeviceTrial(request)
        val accessToken = requireNotNull(response.accessToken)
        require(accessToken.length in 32..MAX_ACCESS_TOKEN_LENGTH) {
            "Invalid access token"
        }
        writeSessionToken(accessToken)
        refreshAccount()
    }

    suspend fun activateMobileTrial(): MobileAccountResponse = authMutex.withLock {
        val token = requireToken()
        apiClient.activateMobileTrial(token)
        refreshAccount()
    }

    suspend fun pollLogin(loginToken: String): LoginPollResult = authMutex.withLock {
        require(loginToken.length in 16..MAX_LOGIN_TOKEN_LENGTH) { "Invalid login token" }
        val response = apiClient.pollStatus(AuthStatusRequest(loginToken))
        when (val state = LoginState.fromWire(response.state)) {
            LoginState.PENDING -> LoginPollResult.Pending(response.pollIntervalSeconds)
            LoginState.EXPIRED -> LoginPollResult.Expired
            LoginState.DENIED -> LoginPollResult.Denied
            LoginState.AUTHENTICATED -> {
                val accessToken = requireNotNull(response.accessToken)
                require(accessToken.length in 32..MAX_ACCESS_TOKEN_LENGTH) {
                    "Invalid access token"
                }
                writeSessionToken(accessToken)
                refreshAccount()
                LoginPollResult.Authenticated
            }
        }
    }

    suspend fun refreshAccount(): MobileAccountResponse {
        val token = requireToken()
        return try {
            apiClient.account(token).also { response ->
                profileMutex.withLock {
                    sessionCacheMutex.withLock {
                        requireCurrentSession(token)
                        reconcileCachedProfile(response)
                        _account.value = response
                    }
                }
            }
        } catch (error: ApiException.Unauthorized) {
            clearAuthentication(expectedToken = token)
            throw error
        }
    }

    suspend fun prepareTunnel(subscriptionId: String): PreparedTunnelProfile =
        profileMutex.withLock {
            require(subscriptionId.matches(UUID_OR_SAFE_ID)) { "Invalid subscription id" }
            val token = requireToken()
            val expectedDeviceId = withContext(Dispatchers.IO) {
                deviceIdentity.deviceId()
            }
            val xrayPrepared = try {
                fetchPreparedTunnelProfile(
                    token = token,
                    subscriptionId = subscriptionId,
                    expectedDeviceId = expectedDeviceId,
                    engine = null,
                )
            } catch (error: ApiException.Unauthorized) {
                clearAuthentication(expectedToken = token)
                throw error
            }
            val relayCapabilityEnabled =
                TunnelEngineKind.LEVIK_RELAY in supportedTunnelEngines &&
                    _account.value?.subscriptions
                        ?.firstOrNull { it.uuid == subscriptionId }
                        ?.capabilities
                        ?.whitelistRelay == true
            val cached = sessionCacheMutex.withLock {
                requireCurrentSession(token)
                _tunnelProfile.value ?: withContext(Dispatchers.IO) { cachedTunnelBlocking() }
            }
            val cachedRelay = cached
                ?.takeIf { relayCapabilityEnabled && it.subscriptionId == subscriptionId }
            val freshRelay = if (relayCapabilityEnabled) {
                try {
                    fetchPreparedTunnelProfile(
                        token = token,
                        subscriptionId = subscriptionId,
                        expectedDeviceId = expectedDeviceId,
                        engine = RELAY_ENGINE_WIRE_NAME,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ApiException.Unauthorized) {
                    clearAuthentication(expectedToken = token)
                    throw error
                } catch (_: Throwable) {
                    AppLogger.w(
                        "AppRepository",
                        "Relay profile refresh failed; retaining cached relay configuration",
                    )
                    null
                }
            } else {
                null
            }
            val base = mergePreparedProfiles(xrayPrepared, freshRelay ?: cachedRelay)
            sessionCacheMutex.withLock {
                requireCurrentSession(token)
                val cachedYandex = cached?.takeIf {
                    it.subscriptionId == subscriptionId && supportsYandex() &&
                        _account.value?.subscriptions?.firstOrNull { sub -> sub.uuid == subscriptionId }
                            ?.let { sub -> sub.capabilities.yandexRelay && sub.isActiveAt(Instant.now()) } == true
                }?.servers?.filter { it.engine == TunnelEngineKind.LEVIK_YANDEX && it.hasUsableYandexAuth() }
                    .orEmpty()
                val prepared = base.copy(servers = base.servers + cachedYandex)
                persistPreparedTunnel(prepared)
                val selected = selectedServerId()
                if (selected == null || prepared.servers.none { it.id == selected }) {
                    val defaultServer = prepared.servers
                        .firstOrNull { it.isEligibleForAutomaticSelection() }
                        ?: prepared.servers.first()
                    selectServer(defaultServer.id)
                }
                _tunnelProfile.value = prepared
                prepared
            }
        }

    private suspend fun fetchPreparedTunnelProfile(
        token: String,
        subscriptionId: String,
        expectedDeviceId: String,
        engine: String?,
    ): PreparedTunnelProfile {
        val envelope = apiClient.tunnelProfile(token, subscriptionId, engine)
        val plaintext = withContext(Dispatchers.IO) {
            profileDecryptor.decrypt(envelope)
        }
        return try {
            val profile = withContext(Dispatchers.Default) {
                profileParser.parse(plaintext, subscriptionId, expectedDeviceId)
            }
            withContext(Dispatchers.Default) {
                tunnelProfilePreparer.prepare(profile)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun persistPreparedTunnel(prepared: PreparedTunnelProfile) {
        val encoded = withContext(Dispatchers.Default) {
            json.encodeToString(PreparedTunnelProfile.serializer(), prepared)
                .encodeToByteArray()
        }
        try {
            withContext(Dispatchers.IO) {
                secureStore.put(SecureFileStore.TUNNEL_PROFILE, encoded)
            }
        } finally {
            encoded.fill(0)
        }
    }

    suspend fun cachedTunnel(): PreparedTunnelProfile? = sessionCacheMutex.withLock {
        val profile = withContext(Dispatchers.IO) { cachedTunnelBlocking() }
        _tunnelProfile.value = profile
        profile
    }

    private fun cachedTunnelBlocking(): PreparedTunnelProfile? {
        val bytes = try {
            secureStore.get(SecureFileStore.TUNNEL_PROFILE)
        } catch (_: Exception) {
            secureStore.remove(SecureFileStore.TUNNEL_PROFILE)
            return null
        } ?: return null

        return try {
            val decoded = json.decodeFromString<PreparedTunnelProfile>(bytes.decodeToString())
            val sanitized = decoded.copy(servers = decoded.servers.filter {
                it.engine in supportedTunnelEngines &&
                    (it.engine != TunnelEngineKind.LEVIK_YANDEX || supportsYandex())
            })
            if (sanitized != decoded) persistPreparedTunnelBlocking(sanitized)
            sanitized
        } catch (_: Exception) {
            secureStore.remove(SecureFileStore.TUNNEL_PROFILE)
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun persistPreparedTunnelBlocking(prepared: PreparedTunnelProfile) {
        val encoded = json.encodeToString(PreparedTunnelProfile.serializer(), prepared)
            .encodeToByteArray()
        try {
            secureStore.put(SecureFileStore.TUNNEL_PROFILE, encoded)
        } finally {
            encoded.fill(0)
        }
    }

    suspend fun selectedServerId(): String? = withContext(Dispatchers.IO) {
        selectedServerIdBlocking()
    }

    private fun selectedServerIdBlocking(): String? =
        try {
            secureStore.get(SecureFileStore.SELECTED_SERVER)
                ?.let { bytes ->
                    try {
                        bytes.decodeToString()
                    } finally {
                        bytes.fill(0)
                    }
                }
        } catch (_: Exception) {
            secureStore.remove(SecureFileStore.SELECTED_SERVER)
            null
        }

    suspend fun selectServer(serverId: String) = withContext(Dispatchers.IO) {
        require(serverId.matches(SAFE_SERVER_ID)) { "Invalid server identifier" }
        secureStore.put(SecureFileStore.SELECTED_SERVER, serverId.encodeToByteArray())
    }

    suspend fun revokeDevice(subscriptionId: String, deviceId: String) {
        val token = requireToken()
        apiClient.revokeDevice(token, subscriptionId, deviceId)
        refreshAccount()
    }

    suspend fun setSubscriptionShield(subscriptionId: String, enabled: Boolean) {
        val token = requireToken()
        apiClient.setSubscriptionShield(token, subscriptionId, enabled)
        refreshAccount()
    }

    suspend fun authorizeActivation(code: String) {
        val token = requireToken()
        apiClient.authorizeActivation(token, code)
    }

    suspend fun fetchFreeProxyLink(): String {
        val response = runCatching { apiClient.freeProxy() }.getOrNull()
        val link = response?.link?.takeIf { response.ok && it.startsWith("tg://") }
        return link ?: DEFAULT_FREE_PROXY_TG_LINK
    }

    suspend fun catalog(): CatalogResponse = apiClient.catalog(requireToken())

    suspend fun syncedSettings(): kotlinx.serialization.json.JsonElement = apiClient.settings(requireToken())

    suspend fun updateSyncedSettings(
        changes: Map<String, kotlinx.serialization.json.JsonPrimitive>,
    ): kotlinx.serialization.json.JsonElement = apiClient.updateSettings(requireToken(), changes)

    /** A one-time link that opens the website signed in; null when it is not a Levik /handoff link. */
    suspend fun webHandoffUrl(target: String): String? =
        apiClient.webHandoff(requireToken(), target).url
            .takeIf(com.leviknet.vpn.core.platform.AppLinks::isHandoffUrl)

    suspend fun createOrder(
        kind: String,
        subscriptionId: String?,
        tariffId: String?,
        months: Int?,
        paymentMethodId: String,
    ): OrderSummary = apiClient.createOrder(
        requireToken(),
        CreateOrderRequest(kind, subscriptionId, tariffId, months, paymentMethodId),
    ).order

    suspend fun openOrderPayment(orderId: Long): String {
        require(orderId > 0) { "Invalid order id" }
        return apiClient.openOrderPayment(requireToken(), orderId).paymentUrl
    }

    suspend fun logout() {
        val token = readToken()
        if (token != null) {
            try {
                apiClient.logout(token)
                withContext(Dispatchers.IO) {
                    secureStore.remove(SecureFileStore.PENDING_REVOCATION_TOKEN)
                }
            } catch (error: ApiException.Unauthorized) {
                withContext(Dispatchers.IO) {
                    secureStore.remove(SecureFileStore.PENDING_REVOCATION_TOKEN)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.IO) {
                    secureStore.put(
                        SecureFileStore.PENDING_REVOCATION_TOKEN,
                        token.encodeToByteArray(),
                    )
                }
            }
        }
        clearLocalSession(keepPendingRevocation = true)
    }

    private suspend fun retryPendingRevocation() {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                secureStore.get(SecureFileStore.PENDING_REVOCATION_TOKEN)
            }.getOrNull()
        } ?: return
        val token = try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
        try {
            apiClient.logout(token)
            withContext(Dispatchers.IO) {
                secureStore.remove(SecureFileStore.PENDING_REVOCATION_TOKEN)
            }
        } catch (_: ApiException.Unauthorized) {
            withContext(Dispatchers.IO) {
                secureStore.remove(SecureFileStore.PENDING_REVOCATION_TOKEN)
            }
        } catch (_: Exception) {
            // Keep the encrypted token for the next foreground retry.
        }
    }

    private suspend fun requireToken(): String =
        readToken() ?: throw ApiException.Unauthorized()

    private suspend fun writeSessionToken(token: String) = sessionCacheMutex.withLock {
        withContext(Dispatchers.IO) {
            val encoded = token.encodeToByteArray()
            try { secureStore.put(SecureFileStore.SESSION_TOKEN, encoded) } finally { encoded.fill(0) }
        }
        _session.value = SessionStatus.Authenticated
    }

    private suspend fun requireCurrentSession(expectedToken: String) {
        if (readToken() != expectedToken) throw ApiException.Unauthorized()
    }

    private suspend fun readToken(): String? = withContext(Dispatchers.IO) {
        readTokenBlocking()
    }

    private fun readTokenBlocking(): String? {
        val bytes = try {
            secureStore.get(SecureFileStore.SESSION_TOKEN)
        } catch (_: Exception) {
            secureStore.remove(SecureFileStore.SESSION_TOKEN)
            return null
        } ?: return null
        return try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun clearLocalSession(keepPendingRevocation: Boolean = false) =
        sessionCacheMutex.withLock {
            withContext(Dispatchers.IO) {
                clearAuthenticationBlocking(keepPendingRevocation)
                clearTunnelProfile()
            }
        }

    private suspend fun clearAuthentication(keepPendingRevocation: Boolean = false, expectedToken: String? = null) =
        sessionCacheMutex.withLock {
            withContext(Dispatchers.IO) {
                if (expectedToken == null || readTokenBlocking() == expectedToken) {
                    clearAuthenticationBlocking(keepPendingRevocation)
                }
            }
        }

    private fun clearAuthenticationBlocking(keepPendingRevocation: Boolean) {
        secureStore.remove(SecureFileStore.SESSION_TOKEN)
        secureStore.remove(SecureFileStore.YANDEX_DOCUMENT)
        if (!keepPendingRevocation) {
            secureStore.remove(SecureFileStore.PENDING_REVOCATION_TOKEN)
        }
        _account.value = null
        _session.value = SessionStatus.SignedOut
    }

    private suspend fun reconcileCachedProfile(account: MobileAccountResponse) {
        // Caller holds profileMutex then sessionCacheMutex; do not re-enter cachedTunnel().
        val cached = withContext(Dispatchers.IO) { cachedTunnelBlocking() } ?: return
        _tunnelProfile.value = cached
        val subscription = account.subscriptions.firstOrNull { it.uuid == cached.subscriptionId }
        if (subscription?.isActiveAt(Instant.now()) != true) {
            withContext(Dispatchers.IO) {
                clearTunnelProfile()
            }
            return
        }
        val relayUnavailable = !subscription.capabilities.whitelistRelay ||
            TunnelEngineKind.LEVIK_RELAY !in supportedTunnelEngines
        val yandexUnavailable = !subscription.capabilities.yandexRelay || !supportsYandex()
        val sanitized = cached.copy(servers = cached.servers.filter {
            (it.engine != TunnelEngineKind.LEVIK_RELAY || !relayUnavailable) &&
                (it.engine != TunnelEngineKind.LEVIK_YANDEX || !yandexUnavailable)
        }, relayCredentialExpiresAt = if (relayUnavailable) null else cached.relayCredentialExpiresAt)
        if (sanitized != cached) {
            persistPreparedTunnel(sanitized)
            val selected = selectedServerId()
            if (sanitized.servers.none { it.id == selected }) {
                sanitized.servers.firstOrNull(TunnelServer::isEligibleForAutomaticSelection)
                    ?.let { selectServer(it.id) }
            }
            _tunnelProfile.value = sanitized
        }
    }

    private fun clearTunnelProfile() {
        secureStore.remove(SecureFileStore.TUNNEL_PROFILE)
        secureStore.remove(SecureFileStore.SELECTED_SERVER)
        _tunnelProfile.value = null
    }

    private fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER
            .replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
            }
        return "$manufacturer ${Build.MODEL}".sanitized(MAX_DEVICE_FIELD_LENGTH)
    }

    private fun String.sanitized(maxLength: Int): String =
        replace(CONTROL_CHARACTERS, " ")
            .replace(MULTIPLE_SPACES, " ")
            .trim()
            .take(maxLength)
            .ifBlank { "Android device" }

    companion object {
        private const val MAX_DEVICE_FIELD_LENGTH = 96
        private const val MAX_LOGIN_TOKEN_LENGTH = 512
        private const val MAX_ACCESS_TOKEN_LENGTH = 4096
        private const val RELAY_ENGINE_WIRE_NAME = "levik-relay"
        private val APP_DATA_DISCLOSURE_CONSENT_VALUE =
            "accepted-v1".encodeToByteArray()
        private val CONTROL_CHARACTERS = Regex("\\p{C}")
        private val MULTIPLE_SPACES = Regex("\\s{2,}")
        private val UUID_OR_SAFE_ID = Regex("[A-Za-z0-9._:-]{8,128}")
        private val SAFE_SERVER_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        const val DEFAULT_FREE_PROXY_TG_LINK =
            "tg://proxy?server=mt.leviknet.com&port=31443&secret=1cb61164c70fc4d193569b05f34e3f7d"
    }
}

internal fun TunnelServer.hasUsableYandexAuth(now: Instant = Instant.now()): Boolean =
    yandexConfig?.bootstrap?.let {
        it.expiresAt > now.epochSecond + YANDEX_AUTH_REFRESH_LEAD_SECONDS &&
            it.providerAuth.validUntil > now.epochSecond + YANDEX_AUTH_REFRESH_LEAD_SECONDS
    } == true

internal fun mergeYandexProfile(base: PreparedTunnelProfile?, yandex: PreparedTunnelProfile): PreparedTunnelProfile {
    require(yandex.servers.size == 1 && yandex.servers.single().engine == TunnelEngineKind.LEVIK_YANDEX)
    if (base == null) return yandex
    require(base.subscriptionId == yandex.subscriptionId)
    return base.copy(
        version = maxOf(base.version, yandex.version),
        profileId = yandex.profileId,
        subscriptionExpiresAt = earliestExpiry(base.subscriptionExpiresAt, yandex.subscriptionExpiresAt),
        servers = base.servers.filter { it.engine != TunnelEngineKind.LEVIK_YANDEX } + yandex.servers,
    )
}

private val REGULAR_PROFILE_ENGINES = setOf(TunnelEngineKind.XRAY, TunnelEngineKind.LEVIK_TUIC)

internal fun mergePreparedProfiles(
    xray: PreparedTunnelProfile,
    relay: PreparedTunnelProfile?,
): PreparedTunnelProfile {
    val relayServers = relay?.servers
        .orEmpty()
        .filter { it.engine == TunnelEngineKind.LEVIK_RELAY }
    if (relayServers.isEmpty()) {
        return withoutRelayServers(xray)
    }
    require(relay?.subscriptionId == xray.subscriptionId) {
        "Relay and Xray subscriptions do not match"
    }
    val profileIds = "${xray.profileId}\u0000${relay.profileId}".encodeToByteArray()
    val compositeId = try {
        MessageDigest.getInstance("SHA-256")
            .digest(profileIds)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    } finally {
        profileIds.fill(0)
    }
    return xray.copy(
        version = maxOf(xray.version, relay.version),
        profileId = "composite:$compositeId",
        subscriptionExpiresAt = earliestExpiry(
            xray.subscriptionExpiresAt,
            relay.subscriptionExpiresAt,
        ),
        relayCredentialExpiresAt = relay.relayCredentialExpiresAt,
        // TUIC servers come from the same regular profile as the Xray servers.
        servers = xray.servers.filter { it.engine in REGULAR_PROFILE_ENGINES } + relayServers,
    )
}

private fun earliestExpiry(first: String?, second: String?): String? =
    listOfNotNull(first, second)
        .minByOrNull { value -> Instant.parse(value) }

internal fun hasUsableRelayCredential(
    profile: PreparedTunnelProfile,
    now: Instant = Instant.now(),
): Boolean {
    val expiresAt = profile.relayCredentialExpiresAt
        ?: profile.servers.firstNotNullOfOrNull { it.relayConfig?.bootstrap?.expiresAt }
        ?: return false
    return runCatching { Instant.parse(expiresAt).isAfter(now) }.getOrDefault(false) &&
        profile.servers.any { it.engine == TunnelEngineKind.LEVIK_RELAY }
}

internal fun withoutRelayServers(profile: PreparedTunnelProfile): PreparedTunnelProfile =
    profile.copy(
        relayCredentialExpiresAt = null,
        servers = profile.servers.filter { it.engine != TunnelEngineKind.LEVIK_RELAY },
    )

internal fun com.leviknet.vpn.core.network.SubscriptionSummary.isActiveAt(
    now: Instant,
): Boolean =
    status.equals("active", ignoreCase = true) &&
        expireAt?.let { value ->
            runCatching { Instant.parse(value).isAfter(now) }.getOrDefault(false)
        } != false

internal fun Iterable<com.leviknet.vpn.core.network.SubscriptionSummary>
    .containsActiveSubscription(
        subscriptionId: String,
        now: Instant,
    ): Boolean =
    any { subscription ->
        subscription.uuid == subscriptionId && subscription.isActiveAt(now)
    }

sealed interface SessionStatus {
    data object Loading : SessionStatus
    data object SignedOut : SessionStatus
    data object Authenticated : SessionStatus
}

sealed interface LoginPollResult {
    data class Pending(val pollIntervalSeconds: Int) : LoginPollResult
    data object Authenticated : LoginPollResult
    data object Expired : LoginPollResult
    data object Denied : LoginPollResult
}
