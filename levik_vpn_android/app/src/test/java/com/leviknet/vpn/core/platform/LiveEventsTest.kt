package com.leviknet.vpn.core.platform

import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEventsTest {
    @Test
    fun `parser emits known events when a blank line completes them`() {
        val parser = EventStreamParser()
        val lines = listOf(
            "retry: 5000", "event: ready", "data: {}", "",
            ": ping", "",
            "event: settings", "data: {}", "",
            "event: wipe", "data: {}", "",
            "data: {}", "",
        )
        assertEquals(listOf(LiveEvent.READY, LiveEvent.SETTINGS), lines.mapNotNull(parser::line))
    }

    private val germany = TunnelServer(
        id = "de-vless", tag = "proxy", name = "Германия", countryCode = "de",
        outbound = JsonObject(
            mapOf("settings" to JsonObject(mapOf("address" to JsonPrimitive("fi1.example.net")))),
        ),
    )

    @Test
    fun `presence names the node and country only while connected`() {
        val connected = PresencePolicy.report(
            VpnSnapshot(state = VpnConnectionState.CONNECTED, serverId = germany.id),
            listOf(germany),
        )
        assertEquals(PresenceReport(true, "n1142bf5571df2373ffb23", "DE"), connected)
        assertEquals(
            PresenceReport(false, null, null),
            PresencePolicy.report(VpnSnapshot(state = VpnConnectionState.CONNECTING, serverId = germany.id), listOf(germany)),
        )
        val unknown = PresencePolicy.report(
            VpnSnapshot(state = VpnConnectionState.CONNECTED, serverId = "gone", serverCountryCode = "Germany"),
            listOf(germany),
        )
        assertEquals(PresenceReport(true, null, null), unknown)
    }

    @Test
    fun `a phone is online while visible or while the vpn runs`() {
        assertTrue(PresencePolicy.online(true, VpnConnectionState.DISCONNECTED))
        assertTrue(PresencePolicy.online(false, VpnConnectionState.CONNECTED))
        assertTrue(PresencePolicy.online(false, VpnConnectionState.PAUSED))
        assertFalse(PresencePolicy.online(false, VpnConnectionState.DISCONNECTED))
    }

    @Test
    fun `retry delays grow and stay bounded`() {
        assertEquals(2_000L, PresencePolicy.retryDelayMs(0, 0.0))
        assertEquals(5_000L, PresencePolicy.retryDelayMs(1, 0.0))
        assertEquals(78_000L, PresencePolicy.retryDelayMs(40, 1.0))
        assertEquals(60_000L, PresencePolicy.retryDelayMs(4, 0.0))
        assertNull(LiveEvent.of("message"))
    }
}
