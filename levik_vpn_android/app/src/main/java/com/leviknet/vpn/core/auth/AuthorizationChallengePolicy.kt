package com.leviknet.vpn.core.auth

import com.leviknet.vpn.core.network.AuthChallengeResponse
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
    private const val TELEGRAM_BOT_USERNAME = "levikvpnbot"
    private val TELEGRAM_START_PARAMETER = Regex("[A-Za-z0-9_-]{1,64}")

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
        val parameters = queryParameters(uri.rawQuery) ?: return null
        val start = parameters["start"]?.takeIf(TELEGRAM_START_PARAMETER::matches)
            ?: return null
        val validTarget = when (uri.scheme?.lowercase()) {
            "https" ->
                uri.host.equals("t.me", ignoreCase = true) &&
                    uri.rawPath.equals("/$TELEGRAM_BOT_USERNAME", ignoreCase = true) &&
                    parameters.keys == setOf("start")
            "tg" ->
                uri.host.equals("resolve", ignoreCase = true) &&
                    parameters["domain"].equals(TELEGRAM_BOT_USERNAME, ignoreCase = true) &&
                    parameters.keys == setOf("domain", "start")
            else -> false
        }
        if (!validTarget) return null
        val nativeUri = "tg://resolve?domain=$TELEGRAM_BOT_USERNAME&start=$start"
        return ChallengeAuthorization.LegacyTelegram(nativeUri, code)
    }

    private fun queryParameters(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrBlank()) return null
        val entries = rawQuery.split('&').map { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) return null
            val name = decode(pair[0]) ?: return null
            val value = decode(pair[1]) ?: return null
            name to value
        }
        if (entries.map { it.first }.distinct().size != entries.size) return null
        return entries.toMap()
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
