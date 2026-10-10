package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.telemetry.AttemptStage
import com.leviknet.vpn.core.telemetry.TelemetryProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelTelemetryTest {
    private fun outbound(protocol: String, network: String? = null, security: String? = null) = buildJsonObject {
        put("protocol", protocol)
        put("streamSettings", buildJsonObject {
            network?.let { put("network", it) }
            security?.let { put("security", it) }
        })
    }

    @Test
    fun `xray outbounds map to contract protocols`() {
        assertEquals(TelemetryProtocol.VLESS_REALITY, xrayTelemetryProtocol(outbound("vless", "tcp", "reality")))
        assertEquals(TelemetryProtocol.VLESS_XHTTP, xrayTelemetryProtocol(outbound("vless", "xhttp", "reality")))
        assertEquals(TelemetryProtocol.VLESS_WS, xrayTelemetryProtocol(outbound("VLESS", "ws", "tls")))
        assertEquals(TelemetryProtocol.VLESS_TCP, xrayTelemetryProtocol(outbound("vless")))
        assertEquals(TelemetryProtocol.HYSTERIA2, xrayTelemetryProtocol(outbound("hysteria2")))
        assertEquals(TelemetryProtocol.OTHER, xrayTelemetryProtocol(buildJsonObject {}))
    }

    @Test
    fun `startup failures use the most specific code`() {
        assertEquals(
            AttemptStage.CORE to "core_unavailable",
            classifyTunnelFailure(TunnelEngineFailureException("relay_native_missing"), AttemptStage.HANDSHAKE, "core_start_failed"),
        )
        assertEquals(
            AttemptStage.PROFILE to "profile_expired",
            classifyTunnelFailure(TunnelEngineFailureException("relay_credential_expired"), AttemptStage.CORE, "x"),
        )
        assertEquals(
            AttemptStage.HANDSHAKE to "relay_turn_refused",
            classifyTunnelFailure(TunnelEngineFailureException("relay_turn_refused"), AttemptStage.CORE, "x"),
        )
        assertEquals(
            AttemptStage.TUN to "unreachable",
            classifyTunnelFailure(
                TunnelNetworkRequirementException(TunnelNetworkRequirementViolation.CELLULAR_NETWORK_REQUIRED),
                AttemptStage.PROFILE,
                "x",
            ),
        )
        assertEquals(AttemptStage.TUN to "permission_denied", classifyTunnelFailure(SecurityException(), AttemptStage.CORE, "x"))
        assertEquals(AttemptStage.TUN to "tun_failed", classifyTunnelFailure(IllegalStateException(), AttemptStage.TUN, "tun_failed"))
    }

    @Test
    fun `attempt progress reports where it stopped`() {
        val progress = AttemptProgress(AttemptStage.PROFILE, "profile_missing")
        progress.reach(AttemptStage.PROFILE, "subscription_expired")

        assertEquals(AttemptStage.PROFILE to "subscription_expired", progress.failure(IllegalArgumentException("expired")))
    }

    @Test
    fun `probe failures use contract codes`() {
        assertEquals("refused", probeFailureCode(java.net.ConnectException()))
        assertEquals("timeout", probeFailureCode(java.net.SocketTimeoutException()))
        assertEquals("reset", probeFailureCode(java.net.SocketException()))
        assertEquals("other", probeFailureCode(IllegalStateException()))
        assertEquals(listOf("no_vpn_network", "http_502", "other"), probeTelemetryCodes(listOf("network_unavailable", "http_502", null)))
    }
}
