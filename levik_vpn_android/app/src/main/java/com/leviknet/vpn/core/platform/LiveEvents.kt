package com.leviknet.vpn.core.platform

import com.leviknet.vpn.vpn.PreparedTunnelProfile
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot

/**
 * Live hints from /api/mobile/v1/events, docs/app-platform.md "Live events".
 * An event only names what changed; the app fetches it. READY (every new
 * stream) and RESYNC mean: fetch everything, something may have been missed.
 */
enum class LiveEvent(val wireName: String) {
    READY("ready"),
    RESYNC("resync"),
    SETTINGS("settings"),
    DEVICES("devices"),
    PRESENCE("presence"),
    CONFIG("config"),
    APPROVALS("approvals"),
    COMMANDS("commands"),
    RULES("rules"),
    SERVICE_ROUTES("service_routes"),
    NOTIFICATIONS("notifications"),

    /** LevikDrop transfers changed, docs/levik-drop.md. */
    DROP("drop"),
    ;

    companion object {
        fun of(name: String): LiveEvent? = entries.firstOrNull { it.wireName == name }
    }
}

/** text/event-stream, one line at a time; returns an event when a blank line completes it. */
class EventStreamParser {
    private var name = DEFAULT_NAME

    fun line(text: String): LiveEvent? {
        if (text.isEmpty()) {
            val completed = LiveEvent.of(name)
            name = DEFAULT_NAME
            return completed
        }
        if (text.startsWith("event:")) name = text.substring(6).trim()
        return null
    }

    private companion object {
        const val DEFAULT_NAME = "message"
    }
}

/**
 * What the account's rules change in a tunnel profile, docs/app-platform.md
 * "Account rules": a running VPN restarts only when this differs.
 */
data class RuleRoutingKey(
    val directDomains: List<String>,
    val proxyDomains: List<String>,
    val directApps: List<String>,
) {
    companion object {
        fun of(profile: PreparedTunnelProfile?): RuleRoutingKey? =
            profile?.let { RuleRoutingKey(it.directDomains, it.proxyDomains, it.directApps) }
    }
}

/** What the devices page shows for this phone. No address, no server name. */
data class PresenceReport(
    val vpnConnected: Boolean,
    val nodeId: String?,
    val country: String?,
)

object PresencePolicy {
    /** The server forgets a device three minutes after its last report. */
    const val REFRESH_MS = 2 * 60 * 1_000L
    const val IDLE_TIMEOUT_MS = 70_000
    private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)
    private val COUNTRY = Regex("^[A-Za-z]{2}$")

    fun report(snapshot: VpnSnapshot, servers: List<TunnelServer>): PresenceReport {
        if (snapshot.state != VpnConnectionState.CONNECTED) return PresenceReport(false, null, null)
        val server = servers.firstOrNull { it.id == snapshot.serverId }
        val country = (snapshot.serverCountryCode ?: server?.countryCode)
            ?.takeIf(COUNTRY::matches)?.uppercase()
        return PresenceReport(true, server?.let(FavoriteNodes::nodeId), country)
    }

    /**
     * A phone is "online" while the app is on the screen or the VPN runs; a
     * stopped app in the background has nothing to report.
     */
    fun online(foreground: Boolean, state: VpnConnectionState): Boolean =
        foreground || state in RUNNING

    /** Grows while the server is unreachable; a stream that delivered events resets it. */
    fun retryDelayMs(failures: Int, jitter: Double): Long {
        val base = RETRY_DELAYS_MS[failures.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
        return base + (base * 0.3 * jitter.coerceIn(0.0, 1.0)).toLong()
    }

    private val RUNNING = setOf(
        VpnConnectionState.CONNECTING,
        VpnConnectionState.CONNECTED,
        VpnConnectionState.RECONNECTING,
        VpnConnectionState.PAUSED,
        VpnConnectionState.LOCKDOWN,
    )
}
