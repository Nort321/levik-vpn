package com.leviknet.vpn.vpn

import java.time.Instant

/** Only the exact ordinary configuration installed in this live core can be reused. */
internal fun hotSwitchEligibleServers(
    installed: List<TunnelServer>,
    installedProfile: PreparedTunnelProfile,
    latestProfile: PreparedTunnelProfile?,
    now: Instant = Instant.now(),
): List<TunnelServer> {
    val latest = latestProfile ?: return emptyList()
    if (latest.subscriptionId != installedProfile.subscriptionId || latest.profileId != installedProfile.profileId) return emptyList()
    if (latest.subscriptionExpiresAt?.let { value -> runCatching { Instant.parse(value).isAfter(now) }.getOrDefault(false) } == false) return emptyList()
    return installed.filter { source ->
        source.engine == TunnelEngineKind.XRAY && source.isSmartAutoCandidate() &&
            latest.servers.firstOrNull { it.id == source.id } == source
    }
}

/** A force reconnect must not tear down a live VPN for a removed target and
 * then silently connect to the first server in a refreshed profile. */
internal fun hotSwitchReconnectTargetExists(
    profile: PreparedTunnelProfile?, subscriptionId: String?, serverId: String?, now: Instant = Instant.now(),
): Boolean = profile != null && subscriptionId != null && profile.subscriptionId == subscriptionId &&
    profile.servers.any { it.id == serverId } && profile.subscriptionExpiresAt?.let { value ->
        runCatching { Instant.parse(value).isAfter(now) }.getOrDefault(false)
    } != false
