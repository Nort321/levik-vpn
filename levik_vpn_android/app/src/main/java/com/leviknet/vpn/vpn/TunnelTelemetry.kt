package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.telemetry.AttemptStage
import com.leviknet.vpn.core.telemetry.TelemetryProtocol
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Maps tunnel state to the fixed codes of docs/connection-telemetry.md.
// Raw errors, hosts and addresses never leave the device.

internal fun TunnelServer.telemetryProtocol(): TelemetryProtocol = when (engine) {
    TunnelEngineKind.LEVIK_TUIC -> TelemetryProtocol.TUIC
    TunnelEngineKind.LEVIK_RELAY -> TelemetryProtocol.RELAY
    TunnelEngineKind.LEVIK_YANDEX -> TelemetryProtocol.YANDEX
    TunnelEngineKind.XRAY -> xrayTelemetryProtocol(outbound)
}

internal fun xrayTelemetryProtocol(outbound: JsonObject): TelemetryProtocol {
    fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase(Locale.ROOT)
    val stream = outbound["streamSettings"] as? JsonObject
    val network = stream?.text("network") ?: "tcp"
    val security = stream?.text("security") ?: "none"
    return when (outbound.text("protocol")) {
        "vless" -> when (network) {
            "xhttp", "splithttp" -> TelemetryProtocol.VLESS_XHTTP
            "ws" -> TelemetryProtocol.VLESS_WS
            "grpc" -> TelemetryProtocol.VLESS_GRPC
            else -> if (security == "reality") TelemetryProtocol.VLESS_REALITY else TelemetryProtocol.VLESS_TCP
        }
        "hysteria", "hysteria2" -> TelemetryProtocol.HYSTERIA2
        "tuic" -> TelemetryProtocol.TUIC
        "trojan" -> TelemetryProtocol.TROJAN
        "shadowsocks" -> TelemetryProtocol.SHADOWSOCKS
        else -> TelemetryProtocol.OTHER
    }
}

/**
 * The step a connection attempt has reached and the code reported if it fails
 * there without a more specific error.
 */
internal class AttemptProgress(stage: AttemptStage, code: String) {
    @Volatile
    var stage: AttemptStage = stage
        private set

    @Volatile
    var code: String = code
        private set

    fun reach(stage: AttemptStage, code: String) {
        this.stage = stage
        this.code = code
    }

    fun failure(error: Throwable): Pair<AttemptStage, String> = classifyTunnelFailure(error, stage, code)
}

internal fun classifyTunnelFailure(
    error: Throwable,
    stage: AttemptStage,
    fallbackCode: String,
): Pair<AttemptStage, String> = when (error) {
    is UnsatisfiedLinkError, is TunnelEngineUnavailableException -> AttemptStage.CORE to "core_unavailable"
    is TunnelEngineFailureException -> when (error.code) {
        "relay_native_missing", "relay_process_start_failed" -> AttemptStage.CORE to "core_unavailable"
        "relay_credential_expired" -> AttemptStage.PROFILE to "profile_expired"
        else -> AttemptStage.HANDSHAKE to error.code
    }
    is TunnelNetworkRequirementException -> AttemptStage.TUN to "unreachable"
    is SecurityException -> AttemptStage.TUN to "permission_denied"
    else -> stage to fallbackCode
}

/** Health probe failure codes; the in-app log keeps the same values. */
internal fun probeFailureCode(error: Exception): String = when (error) {
    is java.net.SocketTimeoutException -> "timeout"
    is java.net.UnknownHostException -> "dns"
    is javax.net.ssl.SSLException -> "tls"
    is java.net.ConnectException -> "refused"
    is java.net.SocketException -> "reset"
    else -> "other"
}

/** "network_unavailable" means Android exposed no VPN network to probe. */
internal fun probeTelemetryCodes(failures: List<String?>): List<String> =
    failures.map { failure ->
        when (failure) {
            null -> "other"
            "network_unavailable" -> "no_vpn_network"
            else -> failure
        }
    }
