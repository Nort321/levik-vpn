package com.leviknet.vpn.core.network

internal const val WHITELIST_MAP_INTERVAL_MS = 15 * 60_000L
internal const val WHITELIST_MAP_MAX_TOKEN_AGE_MS = 60 * 60_000L

internal fun whitelistMapSignal(mode: WhitelistMode): String? = when (mode) {
    WhitelistMode.ACTIVE -> "active"
    WhitelistMode.INACTIVE -> "inactive"
    WhitelistMode.UNKNOWN -> null
}

internal fun whitelistRegionTokenIsUsable(
    sameNetwork: Boolean,
    receivedElapsedMs: Long,
    expiresElapsedMs: Long,
    nowElapsedMs: Long,
): Boolean = sameNetwork &&
    nowElapsedMs >= receivedElapsedMs &&
    nowElapsedMs < expiresElapsedMs &&
    nowElapsedMs - receivedElapsedMs < WHITELIST_MAP_MAX_TOKEN_AGE_MS
