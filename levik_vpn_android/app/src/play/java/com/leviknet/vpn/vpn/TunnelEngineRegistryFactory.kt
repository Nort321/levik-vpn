package com.leviknet.vpn.vpn

import android.content.Context

internal fun createTunnelEngineRegistry(
    xrayRuntime: XrayRuntime,
    @Suppress("UNUSED_PARAMETER") nativeLibraryDir: String,
    @Suppress("UNUSED_PARAMETER") appContext: Context? = null,
): TunnelEngineRegistry = TunnelEngineRegistry(
    listOf(XrayTunnelEngineAdapter(xrayRuntime)),
)
