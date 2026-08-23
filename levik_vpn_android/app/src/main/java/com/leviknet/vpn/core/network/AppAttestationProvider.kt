package com.leviknet.vpn.core.network

interface AppAttestationProvider {
    suspend fun token(requestHash: String): AttestationResult
}

sealed interface AttestationResult {
    data class Available(val token: String) : AttestationResult
    data class Unavailable(val cause: Throwable? = null) : AttestationResult
}

internal object AppAttestationPolicy {
    fun requiresIntegrity(
        playIntegrityEnabled: Boolean,
        isDebugBuild: Boolean,
    ): Boolean = playIntegrityEnabled && !isDebugBuild
}
