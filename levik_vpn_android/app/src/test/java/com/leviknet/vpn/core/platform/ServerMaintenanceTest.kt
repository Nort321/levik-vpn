package com.leviknet.vpn.core.platform

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMaintenanceTest {
    private val node = "n1" + "a".repeat(20)
    private val other = "n1" + "b".repeat(20)
    private val start = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-10-14T13:00:00Z").toEpochMilli()
    private val window = MaintenanceWindow(node, start, end)

    @Test
    fun `parses valid windows and drops the rest`() {
        val parsed = ServerMaintenance.parse(
            Json.parseToJsonElement(
                """
                [
                  {"nodeId": "$node", "startsAt": "2026-10-14T12:00:00Z", "endsAt": "2026-10-14T13:00:00Z"},
                  {"nodeId": "NODE", "startsAt": "2026-10-14T12:00:00Z", "endsAt": "2026-10-14T13:00:00Z"},
                  {"nodeId": "$other", "startsAt": "2026-10-14T13:00:00Z", "endsAt": "2026-10-14T12:00:00Z"},
                  {"nodeId": "$other", "startsAt": "2026-10-14T12:00:00Z", "endsAt": "2026-10-16T12:00:00Z"},
                  {"nodeId": "$other", "startsAt": "soon", "endsAt": "2026-10-14T13:00:00Z"},
                  {"nodeId": "$other", "startsAt": 1, "endsAt": 2},
                  "text"
                ]
                """,
            ),
        )
        assertEquals(listOf(window), parsed)
        assertEquals(emptyList<MaintenanceWindow>(), ServerMaintenance.parse(null))
        assertEquals(emptyList<MaintenanceWindow>(), ServerMaintenance.parse(Json.parseToJsonElement("{}")))
    }

    @Test
    fun `remote configuration carries the windows`() {
        val config = RemoteConfigPolicy.parse(
            Json.parseToJsonElement(
                """{"ok": true, "maintenance": [{"nodeId": "$node", "startsAt": "2026-10-14T12:00:00Z", "endsAt": "2026-10-14T13:00:00Z"}]}""",
            ),
        )
        assertEquals(listOf(window), config?.maintenance)
        assertEquals(emptyList<MaintenanceWindow>(), RemoteConfigPolicy.parse(Json.parseToJsonElement("""{"ok": true}"""))?.maintenance)
    }

    @Test
    fun `keeps the app off a node from the lead time until the end`() {
        val windows = listOf(window)
        assertNull(ServerMaintenance.windowFor(windows, node, start - ServerMaintenance.LEAD_MS - 1))
        assertEquals(window, ServerMaintenance.windowFor(windows, node, start - ServerMaintenance.LEAD_MS))
        assertEquals(window, ServerMaintenance.windowFor(windows, node, end - 1))
        assertNull(ServerMaintenance.windowFor(windows, node, end))
        assertNull(ServerMaintenance.windowFor(windows, other, start))
        assertNull(ServerMaintenance.windowFor(windows, null, start))
    }

    @Test
    fun `moves while idle and two minutes before the start at the latest`() {
        assertEquals(MaintenanceMove.STAY, ServerMaintenance.move(null, start))
        assertEquals(MaintenanceMove.WHEN_IDLE, ServerMaintenance.move(window, start - ServerMaintenance.FORCE_MS - 1))
        assertEquals(MaintenanceMove.NOW, ServerMaintenance.move(window, start - ServerMaintenance.FORCE_MS))
        assertEquals(MaintenanceMove.NOW, ServerMaintenance.move(window, start + 1))
    }

    @Test
    fun `avoids servers under maintenance unless none would remain`() {
        val windows = listOf(window)
        val servers = listOf(node, other)
        assertEquals(listOf(other), ServerMaintenance.avoid(servers, windows, { it }, start))
        assertEquals(listOf(node), ServerMaintenance.avoid(listOf(node), windows, { it }, start))
        assertEquals(servers, ServerMaintenance.avoid(servers, emptyList(), { it }, start))
        assertEquals(servers, ServerMaintenance.avoid(servers, windows, { it }, end))
    }

    @Test
    fun `counts a connection as idle only after a quiet stretch`() {
        val meter = IdleMeter()
        val at = 1_000_000L
        meter.record(0, at)
        meter.record(10_000, at + 10_000)
        assertFalse(meter.idle(at + 10_000))
        meter.record(20_000, at + 20_000)
        assertTrue(meter.idle(at + 20_000))

        // A busy stretch.
        meter.record(20_000 + 5_000_000, at + 40_000)
        assertFalse(meter.idle(at + 40_000))

        // Counters start again with a new connection.
        meter.record(100, at + 41_000)
        assertFalse(meter.idle(at + 41_000))
        meter.record(200, at + 61_000)
        assertTrue(meter.idle(at + 61_000))

        meter.reset()
        assertFalse(meter.idle(at + 61_000))
    }
}
