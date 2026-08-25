package com.leviknet.vpn.data

import android.os.Build
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.AuthChallengeRequest
import com.leviknet.vpn.core.network.AuthChallengeResponse
import com.leviknet.vpn.core.network.AuthStatusRequest
import com.leviknet.vpn.core.network.CatalogResponse
import com.leviknet.vpn.core.network.CreateOrderRequest
import com.leviknet.vpn.core.network.OrderSummary
import com.leviknet.vpn.core.network.LoginState
import com.leviknet.vpn.core.network.MobileAccountResponse
import com.leviknet.vpn.core.network.MobileApiClient
import com.leviknet.vpn.core.security.DeviceIdentity
import com.leviknet.vpn.core.security.HybridProfileDecryptor
import com.leviknet.vpn.core.security.SecureFileStore
import com.leviknet.vpn.vpn.PreparedTunnelProfile
import com.leviknet.vpn.vpn.TunnelProfileParser
import com.leviknet.vpn.vpn.XrayRuntime
import java.time.Instant
import java.util.Locale
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
    private val secureStore: SecureFileStore,
    private val profileDecryptor: HybridProfileDecryptor,
    private val xrayRuntime: XrayRuntime,
    private val json: Json,
) {
    private val profileParser = TunnelProfileParser(json)
    private val authMutex = Mutex()
    private val profileMutex = Mutex()
    private val _session = MutableStateFlow<SessionStatus>(SessionStatus.Loading)
    private val _account = MutableStateFlow<MobileAccountResponse?>(null)
    private val _tunnelProfile = MutableStateFlow<PreparedTunnelProfile?>(null)

    val session: StateFlow<SessionStatus> = _session.asStateFlow()
    val account: StateFlow<MobileAccountResponse?> = _account.asStateFlow()
    val tunnelProfile: StateFlow<PreparedTunnelProfile?> = _tunnelProfile.asStateFlow()

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
                    clearAuthentication()
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

    suspend fun beginLogin(): AuthChallengeResponse = authMutex.withLock {
        val request = withContext(Dispatchers.IO) {
            AuthChallengeRequest(
                accountActivationSupported = false,
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

    suspend fun activateTrial(): MobileAccountResponse = authMutex.withLock {
        val token = requireToken()
        apiClient.activateTrial(token)
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
                withContext(Dispatchers.IO) {
                    secureStore.put(
                        SecureFileStore.SESSION_TOKEN,
                        accessToken.encodeToByteArray(),
                    )
                }
                _session.value = SessionStatus.Authenticated
                refreshAccount()
                LoginPollResult.Authenticated
            }
        }
    }

    suspend fun refreshAccount(): MobileAccountResponse {
        val token = requireToken()
        return try {
            apiClient.account(token).also { response ->
                reconcileCachedProfile(response)
                _account.value = response
            }
        } catch (error: ApiException.Unauthorized) {
            clearAuthentication()
            throw error
        }
    }

    suspend fun prepareTunnel(subscriptionId: String): PreparedTunnelProfile =
        profileMutex.withLock {
            require(subscriptionId.matches(UUID_OR_SAFE_ID)) { "Invalid subscription id" }
            val token = requireToken()
            val envelope = try {
                apiClient.tunnelProfile(token, subscriptionId)
            } catch (error: ApiException.Unauthorized) {
                clearAuthentication()
                throw error
            }
            val plaintext = withContext(Dispatchers.IO) {
                profileDecryptor.decrypt(envelope)
            }
            try {
                val profile = withContext(Dispatchers.Default) {
                    profileParser.parse(plaintext, subscriptionId)
                }
                val prepared = withContext(Dispatchers.Default) {
                    xrayRuntime.convertProfile(profile)
                }
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
                val selected = selectedServerId()
                if (selected == null || prepared.servers.none { it.id == selected }) {
                    selectServer(prepared.servers.first().id)
                }
                _tunnelProfile.value = prepared
                prepared
            } finally {
                plaintext.fill(0)
            }
        }

    suspend fun cachedTunnel(): PreparedTunnelProfile? {
        val profile = withContext(Dispatchers.IO) { cachedTunnelBlocking() }
        _tunnelProfile.value = profile
        return profile
    }

    private fun cachedTunnelBlocking(): PreparedTunnelProfile? {
        val bytes = try {
            secureStore.get(SecureFileStore.TUNNEL_PROFILE)
        } catch (_: Exception) {
            secureStore.remove(SecureFileStore.TUNNEL_PROFILE)
            return null
        } ?: return null

        return try {
            json.decodeFromString<PreparedTunnelProfile>(bytes.decodeToString())
        } catch (_: Exception) {
            secureStore.remove(SecureFileStore.TUNNEL_PROFILE)
            null
        } finally {
            bytes.fill(0)
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
        require(serverId.matches(SHA_256_HEX)) { "Invalid server identifier" }
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

    suspend fun fetchFreeProxyLink(): String {
        val response = runCatching { apiClient.freeProxy() }.getOrNull()
        val link = response?.link?.takeIf { response.ok && it.startsWith("tg://") }
        return link ?: DEFAULT_FREE_PROXY_TG_LINK
    }

    suspend fun catalog(): CatalogResponse = apiClient.catalog(requireToken())

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
        withContext(Dispatchers.IO) {
            clearAuthenticationBlocking(keepPendingRevocation)
            clearTunnelProfile()
        }

    private suspend fun clearAuthentication(keepPendingRevocation: Boolean = false) =
        withContext(Dispatchers.IO) {
            clearAuthenticationBlocking(keepPendingRevocation)
        }

    private fun clearAuthenticationBlocking(keepPendingRevocation: Boolean) {
        secureStore.remove(SecureFileStore.SESSION_TOKEN)
        if (!keepPendingRevocation) {
            secureStore.remove(SecureFileStore.PENDING_REVOCATION_TOKEN)
        }
        _account.value = null
        _session.value = SessionStatus.SignedOut
    }

    private suspend fun reconcileCachedProfile(account: MobileAccountResponse) {
        val cached = cachedTunnel() ?: return
        val subscriptionStillOwned = account.subscriptions.containsActiveSubscription(
            subscriptionId = cached.subscriptionId,
            now = Instant.now(),
        )
        if (!subscriptionStillOwned) {
            withContext(Dispatchers.IO) {
                clearTunnelProfile()
            }
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
        private val APP_DATA_DISCLOSURE_CONSENT_VALUE =
            "accepted-v1".encodeToByteArray()
        private val CONTROL_CHARACTERS = Regex("\\p{C}")
        private val MULTIPLE_SPACES = Regex("\\s{2,}")
        private val UUID_OR_SAFE_ID = Regex("[A-Za-z0-9._:-]{8,128}")
        private val SHA_256_HEX = Regex("[a-f0-9]{64}")
        const val DEFAULT_FREE_PROXY_TG_LINK =
            "tg://proxy?server=mt.leviknet.com&port=31443&secret=1cb61164c70fc4d193569b05f34e3f7d"
    }
}

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
