package com.leviknet.vpn.core.update

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestVerifierTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val signingCertificateSha256 = "ab".repeat(32)
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }
    private val apkUrl = "$RELEASE_PREFIX/levik-vpn-direct.apk"
    private val apkSize = 200L * 1024 * 1024
    private val publishedAssets = listOf(
        PublishedReleaseAsset("levik-vpn-direct.apk", apkUrl, apkSize),
    )

    @Test
    fun `verifies exact raw manifest bytes and defaults force update to false`() {
        val manifestBytes = manifestBytes(validManifest())
        val update = verifier().verify(
            manifestBytes = manifestBytes,
            signatureBytes = sign(manifestBytes),
            releaseAssets = publishedAssets,
        )

        assertEquals("com.leviknet.vpn", update.packageName)
        assertEquals(20, update.latestVersionCode)
        assertEquals(apkSize, update.apkSize)
        assertFalse(update.forceUpdate)
    }

    @Test
    fun `rejects a manifest changed after signing`() {
        val manifestBytes = manifestBytes(validManifest())
        val modified = manifestBytes + '\n'.code.toByte()

        assertThrows(UpdateVerificationException::class.java) {
            verifier().verify(modified, sign(manifestBytes), publishedAssets)
        }
    }

    @Test
    fun `rejects cross package and downgrade manifests`() {
        val crossPackage = manifestBytes(validManifest().copy(packageName = "com.example.other"))
        assertThrows(UpdateVerificationException::class.java) {
            verifier().verify(crossPackage, sign(crossPackage), publishedAssets)
        }

        val downgrade = manifestBytes(validManifest().copy(versionCode = 19))
        assertThrows(UpdateNotNewerException::class.java) {
            verifier().verify(downgrade, sign(downgrade), publishedAssets)
        }
    }

    @Test
    fun `rejects untrusted origin and mismatched published size`() {
        val foreignUrl = "https://example.com/downloads/android/stable/v2.0.0/app.apk"
        val foreignManifest = manifestBytes(validManifest().copy(apkUrl = foreignUrl))
        assertThrows(UpdateVerificationException::class.java) {
            verifier().verify(
                foreignManifest,
                sign(foreignManifest),
                listOf(PublishedReleaseAsset("app.apk", foreignUrl, apkSize)),
            )
        }

        val valid = manifestBytes(validManifest())
        assertThrows(UpdateVerificationException::class.java) {
            verifier().verify(
                valid,
                sign(valid),
                listOf(PublishedReleaseAsset("levik-vpn-direct.apk", apkUrl, apkSize + 1)),
            )
        }
    }

    @Test
    fun `rejects encoded or nested release asset paths`() {
        val invalidUrls = listOf(
            "https://leviknet.com/downloads/android/stable/v2.0.0/nested/app.apk",
            "https://leviknet.com/downloads/android/stable/v2.0.0%2fescape/app.apk",
            "https://leviknet.com/downloads/android/stable/%2e%2e/app.apk",
            "https://leviknet.com/downloads/android/stable/../app.apk",
        )

        invalidUrls.forEach { invalidUrl ->
            val manifest = manifestBytes(validManifest().copy(apkUrl = invalidUrl))
            assertThrows(UpdateVerificationException::class.java) {
                verifier().verify(
                    manifest,
                    sign(manifest),
                    listOf(PublishedReleaseAsset("app.apk", invalidUrl, apkSize)),
                )
            }
        }
    }

    @Test
    fun `rejects a certificate pin not matching the build configuration`() {
        val manifest = manifestBytes(
            validManifest().copy(signingCertificateSha256 = "cd".repeat(32)),
        )

        assertThrows(UpdateVerificationException::class.java) {
            verifier().verify(manifest, sign(manifest), publishedAssets)
        }
    }

    private fun verifier() = UpdateManifestVerifier(
        publicKeyBase64 = publicKeyBase64,
        expectedSigningCertificateSha256 = signingCertificateSha256,
        expectedPackageName = "com.leviknet.vpn",
        currentVersionCode = 19,
    )

    private fun validManifest() = DirectUpdateManifest(
        schemaVersion = 1,
        packageName = "com.leviknet.vpn",
        versionCode = 20,
        versionName = "2.0.0",
        apkUrl = apkUrl,
        apkSize = apkSize,
        apkSha256 = "12".repeat(32),
        signingCertificateSha256 = signingCertificateSha256,
    )

    private fun manifestBytes(manifest: DirectUpdateManifest): ByteArray =
        json.encodeToString(manifest).encodeToByteArray()

    private fun sign(bytes: ByteArray): ByteArray {
        val signature = Signature.getInstance(UpdateManifestVerifier.SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(bytes)
            sign()
        }
        return Base64.getEncoder().encodeToString(signature).encodeToByteArray()
    }

    companion object {
        private const val RELEASE_PREFIX =
            "https://leviknet.com/downloads/android/stable/v2.0.0"
    }
}
