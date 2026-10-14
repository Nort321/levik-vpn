package com.leviknet.vpn.core.platform

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Planned work on a server, docs/app-platform.md "Server maintenance": the
 * app stops choosing the server and moves off it before the window starts,
 * while the connection is idle or two minutes before the start at the latest.
 */
data class MaintenanceWindow(val nodeId: String, val startsAtMs: Long, val endsAtMs: Long)

enum class MaintenanceMove { STAY, WHEN_IDLE, NOW }

object ServerMaintenance {
    /** From this long before the start the server is not chosen and an idle connection moves. */
    const val LEAD_MS = 15 * 60_000L

    /** From this long before the start the connection moves even while busy. */
    const val FORCE_MS = 2 * 60_000L

    /** A connection counts as idle below this many bytes per second, both ways together... */
    const val IDLE_BYTES_PER_SECOND = 4 * 1024L

    /** ...for this long. */
    const val IDLE_WINDOW_MS = 20_000L

    private const val MAX_WINDOWS = 50
    private const val MAX_WINDOW_MS = 24 * 60 * 60_000L
    private val NODE_ID = Regex("^n1[0-9a-f]{20}$")

    fun parse(value: JsonElement?): List<MaintenanceWindow> = (value as? JsonArray).orEmpty()
        .take(MAX_WINDOWS)
        .mapNotNull { item ->
            val window = item as? JsonObject ?: return@mapNotNull null
            val nodeId = window.text("nodeId")?.takeIf(NODE_ID::matches) ?: return@mapNotNull null
            val starts = window.text("startsAt")?.let(::instantMs) ?: return@mapNotNull null
            val ends = window.text("endsAt")?.let(::instantMs) ?: return@mapNotNull null
            if (ends <= starts || ends - starts > MAX_WINDOW_MS) return@mapNotNull null
            MaintenanceWindow(nodeId, starts, ends)
        }

    /** The window that keeps the app off this node now: from the lead time before it until its end. */
    fun windowFor(windows: List<MaintenanceWindow>, nodeId: String?, nowMs: Long): MaintenanceWindow? {
        if (nodeId == null) return null
        return windows.firstOrNull { it.nodeId == nodeId && nowMs >= it.startsAtMs - LEAD_MS && nowMs < it.endsAtMs }
    }

    /** Leaves out servers under maintenance, unless that would leave none. */
    fun <Server> avoid(
        servers: List<Server>,
        windows: List<MaintenanceWindow>,
        nodeIdOf: (Server) -> String?,
        nowMs: Long,
    ): List<Server> {
        if (windows.isEmpty()) return servers
        return servers.filter { windowFor(windows, nodeIdOf(it), nowMs) == null }.ifEmpty { servers }
    }

    fun move(window: MaintenanceWindow?, nowMs: Long): MaintenanceMove = when {
        window == null -> MaintenanceMove.STAY
        nowMs >= window.startsAtMs - FORCE_MS -> MaintenanceMove.NOW
        else -> MaintenanceMove.WHEN_IDLE
    }

    private fun instantMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
}

/** Byte counters over the last seconds; tells whether the connection carries anything. */
class IdleMeter {
    private data class Sample(val atMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun record(totalBytes: Long, nowMs: Long) {
        // Counters restart with a new connection.
        if (samples.lastOrNull()?.let { totalBytes < it.bytes } == true) samples.clear()
        samples.addLast(Sample(nowMs, totalBytes))
        val keepFrom = nowMs - ServerMaintenance.IDLE_WINDOW_MS * 2
        while (samples.size > 2 && samples[1].atMs <= keepFrom) samples.removeFirst()
    }

    @Synchronized
    fun reset() = samples.clear()

    @Synchronized
    fun idle(nowMs: Long): Boolean {
        val first = samples.firstOrNull { it.atMs >= nowMs - ServerMaintenance.IDLE_WINDOW_MS - 1_000L }
        val last = samples.lastOrNull()
        if (first == null || last == null || last.atMs - first.atMs < ServerMaintenance.IDLE_WINDOW_MS) return false
        val seconds = (last.atMs - first.atMs) / 1_000.0
        return (last.bytes - first.bytes) / seconds < ServerMaintenance.IDLE_BYTES_PER_SECOND
    }
}

/** The connection moved from one server to another ahead of maintenance. */
data class MaintenanceSwitch(val fromServerId: String, val toServerId: String)
