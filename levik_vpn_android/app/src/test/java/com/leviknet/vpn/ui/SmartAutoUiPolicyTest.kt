package com.leviknet.vpn.ui

import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoUiPolicyTest {
    @Test
    fun `refresh and selection preserve an existing catalog while first load may show progress`() {
        assertFalse(showInitialServerLoading(loading = true, hasCatalog = true))
        assertTrue(showInitialServerLoading(loading = true, hasCatalog = false))
        assertFalse(showInitialServerLoading(loading = false, hasCatalog = false))
    }

    @Test
    fun `background preparation cannot hide a live connection`() {
        val vpn = VpnSnapshot(state = VpnConnectionState.CONNECTED)
        assertEquals(SmartAutoUiStatus.CONNECTED, smartAutoUiStatus(vpn, selecting = true))
        assertEquals(SmartAutoUiStatus.READY, smartAutoUiStatus(VpnSnapshot(), selecting = false))
        assertEquals(SmartAutoUiStatus.PREPARING, smartAutoUiStatus(VpnSnapshot(), selecting = true))
    }

    @Test
    fun `uncertain and traffic blocked connections never claim healthy auto operation`() {
        val vpn = VpnSnapshot(state = VpnConnectionState.CONNECTED)
        assertEquals(SmartAutoUiStatus.RECOVERING, smartAutoUiStatus(vpn.copy(mainSelectionCertain = false), false))
        assertEquals(SmartAutoUiStatus.RECOVERING, smartAutoUiStatus(vpn.copy(trafficBlockedServerId = "a"), false))
        assertEquals(SmartAutoUiStatus.RECOVERING, smartAutoUiStatus(vpn.copy(state = VpnConnectionState.LOCKDOWN), false))
        assertEquals(SmartAutoUiStatus.PAUSED, smartAutoUiStatus(vpn.copy(state = VpnConnectionState.PAUSED), false))
    }
}
