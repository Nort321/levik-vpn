package com.leviknet.vpn.vpn

import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class TuicTunnelEngineAdapter(
    executablePath: String,
    xrayRuntime: XrayRuntime,
) : TunnelEngineAdapter by LoopbackSocksTunnelEngineAdapter(
    kind = TunnelEngineKind.LEVIK_TUIC,
    protocol = "tuic",
    xrayRuntime = xrayRuntime,
    createSession = { request, environment ->
        require(request is TunnelEngineRequest.Tuic)
        tuicSession(executablePath, request.config, environment)
    },
    createProbe = { server, environment ->
        server.tuicConfig?.let { tuicSession(executablePath, it, environment) }
    },
)

private fun tuicSession(path: String, config: TuicServerConfig, environment: TunnelEngineEnvironment): NativeSocksSession =
    AndroidSocksNativeSession(path, "tuic", environment) {
        put("server", config.address)
        put("server_port", config.port)
        put("uuid", config.uuid)
        put("password", config.password)
        put("congestion_control", config.congestionControl)
        put("udp_relay_mode", config.udpRelayMode)
        put("heartbeat", "10s")
        putJsonObject("tls") {
            put("enabled", true)
            put("server_name", config.serverName)
            putJsonArray("alpn") { config.alpn.forEach { add(it) } }
            putJsonArray("certificate") { add(config.caCertificatePem) }
        }
    }
