package com.leviknet.vpn.core.auth

import com.leviknet.vpn.core.network.AuthChallengeResponse
import java.net.URI

sealed interface ChallengeAuthorization {
    val uri: String
    val code: String

    data class AccountActivation(
        override val uri: String,
        override val code: String,
    ) : ChallengeAuthorization

    data class LegacyTelegram(
        override val uri: String,
        override val code: String,
    ) : ChallengeAuthorization
}

internal object AuthorizationChallengePolicy {
    private const val MAX_LEGACY_URI_LENGTH = 2_048

    fun resolve(challenge: AuthChallengeResponse): ChallengeAuthorization? {
        if (challenge.loginToken.isBlank()) return null
        return if (challenge.accountActivationSupported) {
            resolveAccountActivation(challenge)
        } else {
            resolveLegacyTelegram(challenge)
        }
    }

    private fun resolveAccountActivation(
        challenge: AuthChallengeResponse,
    ): ChallengeAuthorization.AccountActivation? {
        val rawUri = challenge.activationUriComplete ?: return null
        val expectedCode = challenge.activationCode ?: return null
        val actualCode = DeepLinkRouter.activationCode(rawUri) ?: return null
        if (actualCode != expectedCode) return null
        if (actualCode.contains(challenge.loginToken) || rawUri.contains(challenge.loginToken)) {
            return null
        }
        return ChallengeAuthorization.AccountActivation(rawUri, actualCode)
    }

    private fun resolveLegacyTelegram(
        challenge: AuthChallengeResponse,
    ): ChallengeAuthorization.LegacyTelegram? {
        val rawUri = challenge.verificationUriComplete ?: return null
        val code = challenge.verificationCode ?: return null
        if (code.isBlank() || rawUri.length > MAX_LEGACY_URI_LENGTH) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        val allowed = when (uri.scheme?.lowercase()) {
            "https" -> uri.host.equals("t.me", ignoreCase = true)
            "tg" -> uri.host.equals("resolve", ignoreCase = true)
            else -> false
        }
        return if (allowed) ChallengeAuthorization.LegacyTelegram(rawUri, code) else null
    }
}
