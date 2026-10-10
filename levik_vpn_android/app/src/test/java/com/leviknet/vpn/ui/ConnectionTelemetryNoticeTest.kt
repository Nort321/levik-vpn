package com.leviknet.vpn.ui

import com.leviknet.vpn.data.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTelemetryNoticeTest {
    private val signedIn = AppUiState(session = SessionStatus.Authenticated, connectionTelemetryNoticeShown = false)

    @Test
    fun `notice waits for sign in and other dialogs`() {
        assertTrue(shouldShowConnectionTelemetryNotice(signedIn))
        assertFalse(shouldShowConnectionTelemetryNotice(signedIn.copy(session = SessionStatus.Loading)))
        assertFalse(shouldShowConnectionTelemetryNotice(signedIn.copy(showVpnDisclosure = true)))
        assertFalse(shouldShowConnectionTelemetryNotice(signedIn.copy(connectionTelemetryNoticeShown = true)))
    }

    @Test
    fun `explicit request shows the consent dialog again`() {
        assertTrue(
            shouldShowConnectionTelemetryNotice(
                AppUiState(connectionTelemetryNoticeShown = true, connectionTelemetryPromptRequested = true),
            ),
        )
    }
}
