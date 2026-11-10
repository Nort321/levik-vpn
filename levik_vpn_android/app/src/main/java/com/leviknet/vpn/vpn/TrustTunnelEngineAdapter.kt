package com.leviknet.vpn.vpn

import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class TrustTunnelEngineAdapter(
    executablePath: String,
    xrayRuntime: XrayRuntime,
    validate: (String, LocalProxyEndpoint) -> Unit = { _, _ -> },
) : TunnelEngineAdapter by LoopbackSocksTunnelEngineAdapter(
    kind = TunnelEngineKind.LEVIK_TRUSTTUNNEL,
    protocol = "trusttunnel",
    xrayRuntime = xrayRuntime,
    createSession = { request, environment ->
        require(request is TunnelEngineRequest.TrustTunnel)
        trustTunnelSession(executablePath, request.config, environment)
    },
    createProbe = { server, environment ->
        server.trustTunnelConfig?.let { trustTunnelSession(executablePath, it, environment) }
    },
    validate = validate,
)

private fun trustTunnelSession(path: String, config: TrustTunnelServerConfig, environment: TunnelEngineEnvironment): NativeSocksSession =
    AndroidSocksNativeSession(path, "trusttunnel", environment) {
        put("server", config.address)
        put("server_port", config.port)
        put("username", config.username)
        put("password", config.password)
        put("quic", config.quic)
        putJsonObject("tls") {
            put("enabled", true)
            put("server_name", config.serverName)
            if (!config.quic) putJsonObject("utls") {
                put("enabled", true)
                put("fingerprint", "chrome")
            }
        }
    }
