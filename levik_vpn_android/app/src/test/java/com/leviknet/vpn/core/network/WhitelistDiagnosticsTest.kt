package com.leviknet.vpn.core.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistDiagnosticsTest {
    @Test
    fun `DNS and TLS causes remain distinguishable without retaining exception messages`() {
        assertEquals(
            WhitelistProbeOutcome.DNS,
            whitelistProbeFailureOutcome(IOException("outer", UnknownHostException("private-host")), readingBody = false),
        )
        assertEquals(
            WhitelistProbeOutcome.TLS,
            whitelistProbeFailureOutcome(IOException("outer", SSLHandshakeException("private-certificate")), readingBody = false),
        )
    }

    @Test
    fun `timeouts identify whether transfer stalled before or during the body`() {
        assertEquals(
            WhitelistProbeOutcome.CONNECT,
            whitelistProbeFailureOutcome(SocketTimeoutException("connect"), readingBody = false),
        )
        assertEquals(
            WhitelistProbeOutcome.READ,
            whitelistProbeFailureOutcome(SocketTimeoutException("read"), readingBody = true),
        )
    }

    @Test
    fun `refusal and reset use the stage where the socket failed`() {
        assertEquals(
            WhitelistProbeOutcome.CONNECT,
            whitelistProbeFailureOutcome(ConnectException("private-address"), readingBody = false),
        )
        assertEquals(
            WhitelistProbeOutcome.READ,
            whitelistProbeFailureOutcome(SocketException("private-address"), readingBody = true),
        )
        assertEquals(
            WhitelistProbeOutcome.READ,
            whitelistProbeFailureOutcome(IOException("unexpected EOF"), readingBody = true),
        )
        assertEquals(
            WhitelistProbeOutcome.OTHER,
            whitelistProbeFailureOutcome(IllegalStateException("private-value"), readingBody = false),
        )
    }

    @Test
    fun `cancellation is not reduced to a completed probe failure`() {
        val cancellation = CancellationException("cancelled scan")
        try {
            whitelistProbeFailureOutcome(cancellation, readingBody = false)
            throw AssertionError("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `foreign probe distinguishes HTTP refusal from an incomplete successful response`() {
        assertEquals(WhitelistProbeOutcome.HTTP, whitelistProbeResponseOutcome(403, 32_768, 32_768))
        assertEquals(WhitelistProbeOutcome.HTTP, whitelistProbeResponseOutcome(302, 0, 32_768))
        assertEquals(WhitelistProbeOutcome.SHORT_BODY, whitelistProbeResponseOutcome(200, 8_192, 32_768))
        assertEquals(WhitelistProbeOutcome.OK, whitelistProbeResponseOutcome(200, 32_768, 32_768))
    }

    @Test
    fun `domestic HTTP responses preserve the existing reachability rule`() {
        assertEquals(WhitelistProbeOutcome.OK, whitelistProbeResponseOutcome(200, 0, 0))
        assertEquals(WhitelistProbeOutcome.OK, whitelistProbeResponseOutcome(403, 0, 0))
        assertEquals(WhitelistProbeOutcome.OK, whitelistProbeResponseOutcome(503, 0, 0))
        assertEquals(WhitelistProbeOutcome.HTTP, whitelistProbeResponseOutcome(-1, 0, 0))
    }

    @Test
    fun `only a successful probe contributes to the reachable count`() {
        WhitelistProbeOutcome.entries.forEach { outcome ->
            val probe = WhitelistProbeDiagnostic("mozilla", false, outcome, 6_000, 8_192, 200)
            if (outcome == WhitelistProbeOutcome.OK) assertTrue(probe.reachable) else assertFalse(probe.reachable)
        }
    }
}
