package com.leviknet.vpn.core.auth

import java.net.URI
import java.util.Locale

internal object ExternalUriPolicy {
    private const val TELEGRAM_HOST = "t.me"
    private const val LEVIKNET_HOST = "leviknet.com"

    fun isAllowedHttpsHost(host: String): Boolean {
        val normalizedHost = host.lowercase(Locale.ROOT)
        return normalizedHost == TELEGRAM_HOST ||
            normalizedHost == LEVIKNET_HOST ||
            normalizedHost.endsWith(".$LEVIKNET_HOST")
    }

    // Payment effects are emitted only from authenticated mobile API responses.
    // The server pins the provider origin; Android still rejects unsafe URL syntax.
    fun isAllowedPaymentUrl(rawUrl: String): Boolean = runCatching {
        val uri = URI(rawUrl)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            uri.fragment == null
    }.getOrDefault(false)
}
