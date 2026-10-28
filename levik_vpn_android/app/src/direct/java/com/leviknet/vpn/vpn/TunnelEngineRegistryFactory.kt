package com.leviknet.vpn.vpn

import android.content.Context

internal fun createTunnelEngineRegistry(
    xrayRuntime: XrayRuntime,
    nativeLibraryDir: String,
    appContext: Context? = null,
): TunnelEngineRegistry = TunnelEngineRegistry(
    listOf(
        XrayTunnelEngineAdapter(xrayRuntime),
        DnsttTunnelEngineAdapter(
            AndroidDnsttNativeSessionFactory(java.io.File(nativeLibraryDir, "liblevikdnstt.so").absolutePath),
            xrayRuntime,
        ),
        RelayTunnelEngineAdapter(
            AndroidRelayNativeSessionFactory(
                java.io.File(nativeLibraryDir, "liblevikrelay.so").absolutePath,
                AndroidRelayVkTurnProvider(appContext?.applicationContext),
            ),
            xrayRuntime,
        ),
        YandexTunnelEngineAdapter(
            AndroidYandexNativeSessionFactory(
                java.io.File(nativeLibraryDir, "liblevikyandex.so").absolutePath,
            ),
            xrayRuntime,
        ),
        TuicTunnelEngineAdapter(
            AndroidTuicNativeSessionFactory(
                java.io.File(nativeLibraryDir, "libleviktuic.so").absolutePath,
            ),
            xrayRuntime,
        ),
        GameTunnelEngineAdapter(
            AndroidGameNativeSessionFactory(
                java.io.File(nativeLibraryDir, "liblevikgame.so").absolutePath,
            ),
            xrayRuntime,
        ),
    ),
)
