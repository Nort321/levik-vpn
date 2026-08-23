package com.leviknet.vpn.core.auth

import com.leviknet.vpn.core.network.AuthChallengeResponse
import com.leviknet.vpn.core.network.AuthChallengeRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationChallengePolicyTest {
    @Test
    fun `new client explicitly advertises account activation capability`() {
        val encoded = Json.encodeToString(
            AuthChallengeRequest(
                accountActivationSupported = true,
                publicKeySpki = "public-key",
                deviceLabel = "device",
                deviceModel = "model",
                deviceOs = "Android",
                appVersion = "2.0.0",
                requestSigningAlgorithm = "RS256",
                profileEncryptionAlgorithm = "RSA-OAEP+A256GCM",
            ),
        )

        assertTrue(encoded.contains("\"accountActivationSupported\":true"))
    }

    @Test
    fun `capable challenge requires matching canonical account activation URI`() {
        val authorization = AuthorizationChallengePolicy.resolve(
            challenge(
                accountActivationSupported = true,
                activationCode = "Account_1234",
                activationUriComplete = "https://leviknet.com/activate?code=Account_1234",
            ),
        )

        assertEquals(
            ChallengeAuthorization.AccountActivation(
                uri = "https://leviknet.com/activate?code=Account_1234",
                code = "Account_1234",
            ),
            authorization,
        )
    }

    @Test
    fun `capable JSON decodes without legacy Telegram fields`() {
        val decoded = Json.decodeFromString<AuthChallengeResponse>(
            """{
                "ok":true,
                "loginToken":"opaque-login-token",
                "accountActivationSupported":true,
                "activationCode":"Account_1234",
                "activationUriComplete":"https://leviknet.com/activate?code=Account_1234",
                "pollIntervalSeconds":2,
                "expiresAt":"2026-08-23T00:00:00Z"
            }""".trimIndent(),
        )

        assertNull(decoded.verificationCode)
        assertNull(decoded.verificationUriComplete)
        assertTrue(
            AuthorizationChallengePolicy.resolve(decoded) is
                ChallengeAuthorization.AccountActivation,
        )
    }

    @Test
    fun `capable challenge fails closed on missing mismatch or login token in URI`() {
        assertNull(
            AuthorizationChallengePolicy.resolve(
                challenge(accountActivationSupported = true),
            ),
        )
        assertNull(
            AuthorizationChallengePolicy.resolve(
                challenge(
                    accountActivationSupported = true,
                    activationCode = "Account_1234",
                    activationUriComplete = "https://leviknet.com/activate?code=Different_1234",
                ),
            ),
        )
        assertNull(
            AuthorizationChallengePolicy.resolve(
                challenge(
                    loginToken = "secret-login-token",
                    accountActivationSupported = true,
                    activationCode = "prefix.secret-login-token.suffix",
                    activationUriComplete =
                        "https://leviknet.com/activate?code=prefix.secret-login-token.suffix",
                ),
            ),
        )
    }

    @Test
    fun `legacy challenge remains compatible only when activation flag is false`() {
        val legacy = AuthorizationChallengePolicy.resolve(
            challenge(
                verificationCode = "123456",
                verificationUriComplete = "https://t.me/levikvpnbot?start=legacy",
            ),
        )
        assertTrue(legacy is ChallengeAuthorization.LegacyTelegram)

        assertNull(
            AuthorizationChallengePolicy.resolve(
                challenge(
                    accountActivationSupported = true,
                    verificationCode = "123456",
                    verificationUriComplete = "https://t.me/levikvpnbot?start=legacy",
                ),
            ),
        )
    }

    @Test
    fun `legacy JSON without capability flag still decodes`() {
        val decoded = Json.decodeFromString<AuthChallengeResponse>(
            """{
                "ok":true,
                "loginToken":"legacy-token",
                "verificationCode":"123456",
                "verificationUriComplete":"https://t.me/levikvpnbot?start=legacy",
                "pollIntervalSeconds":2,
                "expiresAt":"2026-08-23T00:00:00Z"
            }""".trimIndent(),
        )

        assertEquals(false, decoded.accountActivationSupported)
        assertTrue(AuthorizationChallengePolicy.resolve(decoded) is ChallengeAuthorization.LegacyTelegram)
    }

    private fun challenge(
        loginToken: String = "login-token-not-in-uri",
        accountActivationSupported: Boolean = false,
        activationCode: String? = null,
        activationUriComplete: String? = null,
        verificationCode: String? = null,
        verificationUriComplete: String? = null,
    ) = AuthChallengeResponse(
        ok = true,
        loginToken = loginToken,
        accountActivationSupported = accountActivationSupported,
        activationCode = activationCode,
        activationUriComplete = activationUriComplete,
        verificationCode = verificationCode,
        verificationUriComplete = verificationUriComplete,
        pollIntervalSeconds = 2,
        expiresAt = "2026-08-23T00:00:00Z",
    )
}
