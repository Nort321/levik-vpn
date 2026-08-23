package com.leviknet.vpn.core.network

import android.content.Context

@Suppress("UNUSED_PARAMETER")
internal fun createAppAttestationProvider(
    context: Context,
    cloudProjectNumber: Long?,
): AppAttestationProvider = UnavailableAppAttestationProvider

internal object UnavailableAppAttestationProvider : AppAttestationProvider {
    override suspend fun token(requestHash: String): AttestationResult =
        AttestationResult.Unavailable()
}
