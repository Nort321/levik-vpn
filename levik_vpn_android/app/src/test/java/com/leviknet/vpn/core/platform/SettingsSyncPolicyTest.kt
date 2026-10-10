package com.leviknet.vpn.core.platform

import com.leviknet.vpn.data.AntiDpiPreset
import com.leviknet.vpn.data.RoutingPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncPolicyTest {
    private val local = LocalSyncedValues(
        routing = RoutingPreset.BYPASS_RU,
        automaticServer = true,
        autoReconnect = true,
        killSwitch = false,
        useDoh = true,
        antiDpiEnabled = true,
        antiDpiPackets = "tlshello",
        antiDpiLength = "100-200",
        antiDpiInterval = "10-20",
    )

    @Test
    fun `encodes every shared field in its wire form`() {
        val encoded = SettingsSyncPolicy.encode(local)
        assertEquals(SettingsSyncPolicy.KEYS.toSet(), encoded.keys)
        assertEquals(JsonPrimitive("bypassRu"), encoded[SettingsSyncPolicy.ROUTING_MODE])
        assertEquals(JsonPrimitive(false), encoded[SettingsSyncPolicy.KILL_SWITCH])
    }

    @Test
    fun `routing modes map both ways`() {
        RoutingPreset.entries.forEach { preset ->
            val wire = SettingsSyncPolicy.encode(local.copy(routing = preset))[SettingsSyncPolicy.ROUTING_MODE]
            assertEquals(preset, SettingsSyncPolicy.routingPreset(wire))
        }
        assertNull(SettingsSyncPolicy.routingPreset(JsonPrimitive("everything")))
    }

    @Test
    fun `rejects values other apps or the server would not accept`() {
        assertFalse(SettingsSyncPolicy.isPortable(SettingsSyncPolicy.ROUTING_MODE, JsonPrimitive("custom")))
        assertFalse(SettingsSyncPolicy.isPortable(SettingsSyncPolicy.KILL_SWITCH, JsonPrimitive("true")))
        assertFalse(SettingsSyncPolicy.isPortable(SettingsSyncPolicy.ANTI_DPI_PACKETS, JsonPrimitive("1-3;rm")))
        assertFalse(SettingsSyncPolicy.isPortable(SettingsSyncPolicy.ANTI_DPI_LENGTH, JsonPrimitive("12345")))
        assertFalse(SettingsSyncPolicy.isPortable("favorites", JsonPrimitive(true)))
        assertTrue(SettingsSyncPolicy.isPortable(SettingsSyncPolicy.ANTI_DPI_INTERVAL, JsonPrimitive("")))
        assertTrue(SettingsSyncPolicy.isPortable(SettingsSyncPolicy.USE_DOH, JsonPrimitive(false)))
    }

    @Test
    fun `changes lists only edited portable fields`() {
        val before = SettingsSyncPolicy.encode(local)
        val after = SettingsSyncPolicy.encode(local.copy(killSwitch = true, antiDpiLength = "999999"))
        assertEquals(
            mapOf(SettingsSyncPolicy.KILL_SWITCH to JsonPrimitive(true)),
            SettingsSyncPolicy.changes(before, after),
        )
    }

    @Test
    fun `remote values do not overwrite unsent edits`() {
        val here = SettingsSyncPolicy.encode(local)
        val remote = here + mapOf(
            SettingsSyncPolicy.KILL_SWITCH to JsonPrimitive(true),
            SettingsSyncPolicy.USE_DOH to JsonPrimitive(false),
        )
        val pending = mapOf(SettingsSyncPolicy.USE_DOH to JsonPrimitive(true))
        assertEquals(
            mapOf(SettingsSyncPolicy.KILL_SWITCH to JsonPrimitive(true)),
            SettingsSyncPolicy.remotePatch(here, remote, pending),
        )
    }

    @Test
    fun `confirmed edits leave the pending set`() {
        val pending = mapOf(
            SettingsSyncPolicy.KILL_SWITCH to JsonPrimitive(true),
            SettingsSyncPolicy.USE_DOH to JsonPrimitive(false),
        )
        val sent = mapOf(
            SettingsSyncPolicy.KILL_SWITCH to JsonPrimitive(true),
            SettingsSyncPolicy.USE_DOH to JsonPrimitive(true),
        )
        assertEquals(
            mapOf(SettingsSyncPolicy.USE_DOH to JsonPrimitive(false)),
            SettingsSyncPolicy.withoutConfirmed(pending, sent),
        )
    }

    @Test
    fun `parses the settings document and drops unknown fields`() {
        val document = SettingsSyncPolicy.parseDocument(
            Json.parseToJsonElement(
                """{"ok":true,"revision":7,"settings":{"killSwitch":true,"routingMode":"nope","theme":"dark"}}""",
            ),
        )
        assertEquals(7L, document?.revision)
        assertEquals(mapOf(SettingsSyncPolicy.KILL_SWITCH to JsonPrimitive(true)), document?.settings)
        assertNull(SettingsSyncPolicy.parseDocument(Json.parseToJsonElement("""{"ok":true,"revision":"7","settings":{}}""")))
        assertNull(SettingsSyncPolicy.parseDocument(Json.parseToJsonElement("""{"ok":false,"revision":1,"settings":{}}""")))
    }

    @Test
    fun `anti-dpi parameters pick a named preset when they match one`() {
        assertEquals(AntiDpiPreset.OFF, SettingsSyncPolicy.antiDpiPreset(false, "1-3", "50-150", "10-20"))
        assertEquals(AntiDpiPreset.BALANCED, SettingsSyncPolicy.antiDpiPreset(true, "1-3", "50-150", "10-20"))
        assertEquals(AntiDpiPreset.TLS_HELLO, SettingsSyncPolicy.antiDpiPreset(true, "tlshello", "100-200", "10-20"))
        assertEquals(AntiDpiPreset.CUSTOM, SettingsSyncPolicy.antiDpiPreset(true, "2", "40", "7"))
    }
}
