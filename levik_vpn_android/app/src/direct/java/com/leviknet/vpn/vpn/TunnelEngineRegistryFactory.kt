package com.leviknet.vpn.vpn

import android.content.Context

internal fun createTunnelEngineRegistry(
    xrayRuntime: XrayRuntime,
    nativeLibraryDir: String,
    appContext: Context? = null,
): TunnelEngineRegistry = TunnelEngineRegistry(
    listOf(
        XrayTunnelEngineAdapter(xrayRuntime),
        RelayTunnelEngineAdapter(
            AndroidRelayNativeSessionFactory(
                java.io.File(nativeLibraryDir, "liblevikrelay.so").absolutePath,
                AndroidRelayVkTurnProvider(appContext?.applicationContext),
            ),
            xrayRuntime,
        ),
    ),
)
