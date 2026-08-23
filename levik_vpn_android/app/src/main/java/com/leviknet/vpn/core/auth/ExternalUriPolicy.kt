package com.leviknet.vpn.core.auth

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
}
