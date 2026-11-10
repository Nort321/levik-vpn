package com.leviknet.vpn.vpn

import android.content.Context

internal fun createTunnelEngineRegistry(
    xrayRuntime: XrayRuntime,
    nativeLibraryDir: String,
    @Suppress("UNUSED_PARAMETER") appContext: Context? = null,
): TunnelEngineRegistry = TunnelEngineRegistry(
    listOf(
        XrayTunnelEngineAdapter(xrayRuntime, PlayTunnelSecurity::validate),
        TrustTunnelEngineAdapter(
            java.io.File(nativeLibraryDir, "libleviktrusttunnel.so").absolutePath,
            xrayRuntime,
            PlayTunnelSecurity::validateTrustTunnel,
        ),
    ),
)
