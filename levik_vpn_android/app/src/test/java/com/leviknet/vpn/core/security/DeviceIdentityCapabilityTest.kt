package com.leviknet.vpn.core.security

import java.security.InvalidKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityCapabilityTest {
    @Test
    fun `fresh api 34 device selects legacy identity`() {
        val kind = DeviceIdentity.selectKeyKind(
            sdkInt = 34,
            modernKeyExists = false,
            legacyKeyExists = false,
        )

        assertEquals(DeviceKeyKind.LEGACY, kind)
        val capability = DeviceIdentity.capabilityFor(kind)
        assertEquals(DeviceIdentity.SIGNING_RS256, capability.requestSigningAlgorithm)
        assertEquals(
            DeviceIdentity.PROFILE_ENCRYPTION_OAEP,
            capability.profileEncryptionAlgorithm,
        )
    }

    @Test
    fun `os upgrade keeps existing legacy identity`() {
        val kind = DeviceIdentity.selectKeyKind(
            sdkInt = 35,
            modernKeyExists = false,
            legacyKeyExists = true,
        )

        assertEquals(DeviceKeyKind.LEGACY, kind)
        val capability = DeviceIdentity.capabilityFor(kind)
        assertEquals(DeviceIdentity.SIGNING_RS256, capability.requestSigningAlgorithm)
        assertEquals(
            DeviceIdentity.PROFILE_ENCRYPTION_OAEP,
            capability.profileEncryptionAlgorithm,
        )
    }

    @Test
    fun `fresh api 35 device selects modern identity`() {
        val kind = DeviceIdentity.selectKeyKind(
            sdkInt = 35,
            modernKeyExists = false,
            legacyKeyExists = false,
        )

        assertEquals(DeviceKeyKind.MODERN, kind)
        val capability = DeviceIdentity.capabilityFor(kind)
        assertEquals(DeviceIdentity.SIGNING_PS256, capability.requestSigningAlgorithm)
        assertEquals(
            DeviceIdentity.PROFILE_ENCRYPTION_OAEP_256,
            capability.profileEncryptionAlgorithm,
        )
    }

    @Test
    fun `existing modern identity wins deterministically`() {
        val kind = DeviceIdentity.selectKeyKind(
            sdkInt = 35,
            modernKeyExists = true,
            legacyKeyExists = true,
        )

        assertEquals(DeviceKeyKind.MODERN, kind)
    }

    @Test
    fun `unsupported modern keystore capability falls back to legacy identity`() {
        assertTrue(
            DeviceIdentity.canFallbackToLegacy(
                DeviceKeyKind.MODERN,
                UnsupportedOperationException("MGF1 is unavailable"),
            ),
        )
        assertFalse(
            DeviceIdentity.canFallbackToLegacy(
                DeviceKeyKind.LEGACY,
                UnsupportedOperationException("MGF1 is unavailable"),
            ),
        )
        assertTrue(
            DeviceIdentity.canFallbackToLegacy(
                DeviceKeyKind.MODERN,
                InvalidKeyException("RSA-PSS is unavailable"),
            ),
        )
    }
}
