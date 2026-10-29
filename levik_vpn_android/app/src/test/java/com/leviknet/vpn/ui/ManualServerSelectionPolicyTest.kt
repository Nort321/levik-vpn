package com.leviknet.vpn.ui

import com.leviknet.vpn.vpn.HotSwitchPhase
import com.leviknet.vpn.vpn.HotSwitchSnapshot
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualServerSelectionPolicyTest {
    private val connected = VpnSnapshot(
        state = VpnConnectionState.CONNECTED,
        subscriptionId = "subscription",
        serverId = "a",
        hotSwitchServerIds = setOf("a", "b"),
    )

    @Test
    fun `only installed connected targets use the hot command without saving a preference first`() {
        assertEquals(ManualServerSelection.HOT_SWITCH, manualServerSelection(connected, "b"))
        assertEquals(ManualServerSelection.RECONNECT, manualServerSelection(connected, "tuic"))
        assertEquals(ManualServerSelection.RECONNECT,
            manualServerSelection(connected.copy(state = VpnConnectionState.RECONNECTING), "b"))
        assertEquals(ManualServerSelection.SAVE_ONLY,
            manualServerSelection(connected.copy(state = VpnConnectionState.DISCONNECTED), "b"))
    }

    @Test
    fun `pending target does not become the confirmed selection`() {
        val preparing = connected.copy(hotSwitch = HotSwitchSnapshot(HotSwitchPhase.PREPARING, "b"))
        assertEquals("a", confirmedServerSelection("a", preparing, "subscription", setOf("a", "b")))
        val acknowledged = preparing.copy(serverId = "b", hotSwitch = HotSwitchSnapshot(HotSwitchPhase.APPLIED, "b"))
        assertEquals("b", confirmedServerSelection("a", acknowledged, "subscription", setOf("a", "b")))
    }

    @Test
    fun `foreign subscription missing target and uncertain route cannot replace the confirmed preference`() {
        val target = connected.copy(serverId = "b")
        assertEquals("a", confirmedServerSelection("a", target, "another-subscription", setOf("a", "b")))
        assertEquals("a", confirmedServerSelection("a", target, "subscription", setOf("a")))
        assertEquals("a", confirmedServerSelection("a", target.copy(mainSelectionCertain = false), "subscription", setOf("a", "b")))
    }

    @Test
    fun `selection labels distinguish compatible target from a restarting protocol before a click`() {
        assertEquals(ServerSwitchHint.HOT_SWITCH, serverSwitchHint(connected, "b"))
        assertEquals(ServerSwitchHint.RECONNECT, serverSwitchHint(connected, "tuic"))
        assertNull(serverSwitchHint(connected, "a"))
        assertEquals(ServerSwitchHint.RECONNECT, serverSwitchHint(connected.copy(hotSwitchServerIds = emptySet()), "a"))
    }

    @Test
    fun `mixed country does not promise that every selectable protocol is hot`() {
        assertEquals(ServerSwitchHint.HOT_SWITCH, countrySwitchHint(connected, listOf("b")))
        assertEquals(ServerSwitchHint.MAY_RECONNECT, countrySwitchHint(connected, listOf("b", "tuic")))
        assertNull(countrySwitchHint(connected.copy(state = VpnConnectionState.DISCONNECTED), listOf("b")))
    }

    @Test
    fun `uncertain acknowledged server remains available as an explicit retry choice`() {
        val unknown = connected.copy(mainSelectionCertain = false,
            hotSwitch = HotSwitchSnapshot(HotSwitchPhase.UNCERTAIN, "b"))
        assertEquals(ServerSwitchHint.HOT_SWITCH, serverSwitchHint(unknown, "a"))
        assertEquals(ServerSwitchHint.HOT_SWITCH, countrySwitchHint(unknown, listOf("a")))
    }

    @Test
    fun `reconnect everything chooses the requested server but cannot race a preparing switch`() {
        val applied = connected.copy(hotSwitch = HotSwitchSnapshot(HotSwitchPhase.APPLIED, "b"))
        assertEquals("b", reconnectAllTarget(applied))
        assertEquals("b", reconnectAllTarget(applied.copy(mainSelectionCertain = false)))
        assertNull(reconnectAllTarget(applied.copy(hotSwitch = HotSwitchSnapshot(HotSwitchPhase.PREPARING, "b"))))
        assertNull(reconnectAllTarget(applied.copy(state = VpnConnectionState.DISCONNECTED)))
    }

    @Test
    fun `pending unknown route stays progress while terminal uncertainty keeps recovery guidance`() {
        val pending = connected.copy(mainSelectionCertain = false, hotSwitch = HotSwitchSnapshot(HotSwitchPhase.PREPARING, "b"))
        assertEquals(HotSwitchPhase.PREPARING, displayedHotSwitchPhase(pending))
        assertNull(reconnectAllTarget(pending))
        assertEquals(HotSwitchPhase.UNCERTAIN, displayedHotSwitchPhase(pending.copy(hotSwitch = null)))
        assertEquals(HotSwitchPhase.UNCERTAIN,
            displayedHotSwitchPhase(pending.copy(hotSwitch = HotSwitchSnapshot(HotSwitchPhase.FAILED, "b"))))
    }

    @Test
    fun `failure to save a preference does not turn an applied route into a failed switch`() {
        val applied = connected.copy(serverId = "b",
            hotSwitch = HotSwitchSnapshot(HotSwitchPhase.APPLIED, "b", preferenceSaved = false))
        assertEquals(HotSwitchPhase.APPLIED, displayedHotSwitchPhase(applied))
        assertEquals("b", confirmedServerSelection("a", applied, "subscription", setOf("a", "b")))
    }
}
