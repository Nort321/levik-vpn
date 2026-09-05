package com.leviknet.vpn.ui

import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayedServerTest {
    @Test
    fun `connected UI uses actual tunnel server`() {
        val state = AppUiState(
            selectedServerId = "planned",
            vpn = VpnSnapshot(
                state = VpnConnectionState.CONNECTED,
                serverId = "actual",
            ),
        )

        assertEquals("actual", displayedServerId(state))
    }

    @Test
    fun `disconnected UI uses saved selection`() {
        val state = AppUiState(
            selectedServerId = "planned",
            vpn = VpnSnapshot(
                state = VpnConnectionState.DISCONNECTED,
                serverId = "stale",
            ),
        )

        assertEquals("planned", displayedServerId(state))
    }
}
