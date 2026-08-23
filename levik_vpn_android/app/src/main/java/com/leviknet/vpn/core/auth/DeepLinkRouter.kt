package com.leviknet.vpn.core.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal enum class DeepLinkDestination {
    ACTIVATION,
}

internal object DeepLinkRouter {
    private const val ACTIVATION_HOST = "leviknet.com"
    private const val ACTIVATION_PATH = "/activate"
    private const val ACTIVATION_CODE_PARAMETER = "code"
    private const val MAX_URI_LENGTH = 2_048
    private const val MAX_RAW_QUERY_LENGTH = 1_024
    private val ACTIVATION_CODE = Regex("[A-Za-z0-9._~-]{8,256}")

    fun route(rawUri: String): DeepLinkDestination? {
        return if (activationCode(rawUri) != null) {
            DeepLinkDestination.ACTIVATION
        } else {
            null
        }
    }

    fun activationCode(rawUri: String): String? {
        if (rawUri.length > MAX_URI_LENGTH) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(ACTIVATION_HOST, ignoreCase = true)) return null
        if (uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (uri.rawPath != ACTIVATION_PATH) return null

        val rawQuery = uri.rawQuery ?: return null
        if (rawQuery.length > MAX_RAW_QUERY_LENGTH) return null
        val queryParts = rawQuery.split('&')
        if (queryParts.size != 1) return null
        val parameter = queryParts.single().split('=', limit = 2)
        if (parameter.size != 2) return null

        val name = decode(parameter[0]) ?: return null
        val code = decode(parameter[1]) ?: return null
        if (name != ACTIVATION_CODE_PARAMETER || !ACTIVATION_CODE.matches(code)) return null

        return code
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
