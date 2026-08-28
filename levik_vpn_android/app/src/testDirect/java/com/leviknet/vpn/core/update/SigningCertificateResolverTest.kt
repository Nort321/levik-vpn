package com.leviknet.vpn.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

class SigningCertificateResolverTest {
    @Test
    fun `uses legacy signatures when signing info is unavailable`() {
        val certificates = resolveCurrentSigningCertificates(
            signingInfoAvailable = false,
            hasMultipleSigners = false,
            apkContentsSigners = emptyList(),
            signingCertificateHistory = emptyList(),
            legacySignatures = listOf("legacy"),
        )

        assertEquals(listOf("legacy"), certificates)
    }

    @Test
    fun `uses latest certificate from single signer history`() {
        val certificates = resolveCurrentSigningCertificates(
            signingInfoAvailable = true,
            hasMultipleSigners = false,
            apkContentsSigners = emptyList(),
            signingCertificateHistory = listOf("original", "current"),
            legacySignatures = listOf("legacy"),
        )

        assertEquals(listOf("current"), certificates)
    }

    @Test
    fun `uses legacy fallback when single signer history is empty`() {
        val certificates = resolveCurrentSigningCertificates(
            signingInfoAvailable = true,
            hasMultipleSigners = false,
            apkContentsSigners = emptyList(),
            signingCertificateHistory = emptyList(),
            legacySignatures = listOf("legacy"),
        )

        assertEquals(listOf("legacy"), certificates)
    }

    @Test
    fun `preserves all current certificates for multiple signer rejection`() {
        val certificates = resolveCurrentSigningCertificates(
            signingInfoAvailable = true,
            hasMultipleSigners = true,
            apkContentsSigners = listOf("first", "second"),
            signingCertificateHistory = emptyList(),
            legacySignatures = listOf("legacy"),
        )

        assertEquals(listOf("first", "second"), certificates)
    }

    @Test
    fun `uses contents signer as final compatible fallback`() {
        val certificates = resolveCurrentSigningCertificates(
            signingInfoAvailable = true,
            hasMultipleSigners = false,
            apkContentsSigners = listOf("current"),
            signingCertificateHistory = emptyList(),
            legacySignatures = emptyList(),
        )

        assertEquals(listOf("current"), certificates)
    }
}
