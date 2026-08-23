package com.leviknet.vpn.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.KeyFactory
import java.security.InvalidAlgorithmParameterException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class DeviceIdentity(
    private val legacyKeyAlias: String = LEGACY_KEY_ALIAS,
    private val modernKeyAlias: String = MODERN_KEY_ALIAS,
) {
    private val selectedKey: SelectedDeviceKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        selectOrCreateKey()
    }
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun deviceId(): String = sha256Hex(keyPair().public.encoded)

    fun publicKeySpkiBase64Url(): String = base64Url(keyPair().public.encoded)

    fun requestSigningAlgorithm(): String =
        selectedKey.capability.requestSigningAlgorithm

    fun profileEncryptionAlgorithm(): String =
        selectedKey.capability.profileEncryptionAlgorithm

    fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance(
            if (requestSigningAlgorithm() == SIGNING_PS256) {
                SHA_256_WITH_RSA_PSS
            } else {
                SHA_256_WITH_RSA
            },
        )
        signature.initSign(privateKey())
        if (requestSigningAlgorithm() == SIGNING_PS256) {
            signature.setParameter(PSS_SHA_256)
        }
        signature.update(payload)
        return base64Url(signature.sign())
    }

    fun decryptProfileKey(algorithm: String, ciphertext: ByteArray): ByteArray {
        require(algorithm == profileEncryptionAlgorithm()) {
            "Profile encryption does not match this device key"
        }
        val useSha256 = algorithm == PROFILE_ENCRYPTION_OAEP_256
        val cipher = Cipher.getInstance(
            if (useSha256) RSA_OAEP_SHA_256_TRANSFORMATION else RSA_OAEP_SHA_1_TRANSFORMATION,
        )
        cipher.init(
            Cipher.DECRYPT_MODE,
            privateKey(),
            if (useSha256) OAEP_SHA_256 else OAEP_SHA_1,
        )
        return cipher.doFinal(ciphertext)
    }

    fun isHardwareBacked(): Boolean {
        val factory = KeyFactory.getInstance(
            keyPair().private.algorithm,
            ANDROID_KEYSTORE,
        )
        return factory.getKeySpec(
            privateKey(),
            android.security.keystore.KeyInfo::class.java,
        ).isInsideSecureHardware
    }

    private fun privateKey(): PrivateKey =
        keyStore.getKey(selectedKey.alias, null) as? PrivateKey
            ?: error("Android Keystore device key is unavailable")

    private fun keyPair(): KeyPair {
        val store = keyStore
        val existing = store.getEntry(selectedKey.alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("Android Keystore device key is unavailable")
        return KeyPair(existing.certificate.publicKey, existing.privateKey)
    }

    private fun generateKeyPair(
        alias: String,
        kind: DeviceKeyKind,
        useStrongBox: Boolean,
    ): KeyPair {
        return try {
            newGenerator(alias, kind, useStrongBox).generateKeyPair()
        } catch (error: Throwable) {
            val canRetry = useStrongBox && (
                error is ProviderException ||
                    error is InvalidAlgorithmParameterException
                )
            if (!canRetry) throw error
            newGenerator(alias, kind, useStrongBox = false).generateKeyPair()
        }
    }

    private fun selectOrCreateKey(): SelectedDeviceKey =
        synchronized(DeviceIdentity::class.java) {
            val store = keyStore
            val kind = selectKeyKind(
                sdkInt = Build.VERSION.SDK_INT,
                modernKeyExists = store.containsAlias(modernKeyAlias),
                legacyKeyExists = store.containsAlias(legacyKeyAlias),
            )
            try {
                selectOrCreateKey(store, kind)
            } catch (error: Throwable) {
                if (!canFallbackToLegacy(kind, error)) throw error
                if (store.containsAlias(modernKeyAlias)) {
                    store.deleteEntry(modernKeyAlias)
                }
                selectOrCreateKey(store, DeviceKeyKind.LEGACY)
            }
        }

    private fun selectOrCreateKey(
        store: KeyStore,
        kind: DeviceKeyKind,
    ): SelectedDeviceKey {
        val alias = if (kind == DeviceKeyKind.MODERN) modernKeyAlias else legacyKeyAlias
        if (!store.containsAlias(alias)) {
            generateKeyPair(
                alias = alias,
                kind = kind,
                useStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
            )
        }
        val selected = SelectedDeviceKey(
            alias = alias,
            capability = capabilityFor(kind),
        )
        verifyKeyCapability(store, selected)
        return selected
    }

    private fun verifyKeyCapability(
        store: KeyStore,
        selected: SelectedDeviceKey,
    ) {
        val entry = store.getEntry(selected.alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("Android Keystore device key is unavailable")
        val modern = selected.capability.requestSigningAlgorithm == SIGNING_PS256
        val signature = Signature.getInstance(
            if (modern) SHA_256_WITH_RSA_PSS else SHA_256_WITH_RSA,
        ).apply {
            initSign(entry.privateKey)
            if (modern) {
                setParameter(PSS_SHA_256)
            }
            update(KEY_CAPABILITY_PROBE)
        }
        signature.sign().fill(0)

        val oaep = if (modern) OAEP_SHA_256 else OAEP_SHA_1
        val transformation = if (modern) {
            RSA_OAEP_SHA_256_TRANSFORMATION
        } else {
            RSA_OAEP_SHA_1_TRANSFORMATION
        }
        val encrypted = Cipher.getInstance(transformation).run {
            init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey, oaep)
            doFinal(KEY_CAPABILITY_PROBE)
        }
        val decrypted = try {
            Cipher.getInstance(transformation).run {
                init(Cipher.DECRYPT_MODE, entry.privateKey, oaep)
                doFinal(encrypted)
            }
        } finally {
            encrypted.fill(0)
        }
        try {
            check(decrypted.contentEquals(KEY_CAPABILITY_PROBE)) {
                "Android Keystore capability probe failed"
            }
        } finally {
            decrypted.fill(0)
        }
    }

    private fun newGenerator(
        alias: String,
        kind: DeviceKeyKind,
        useStrongBox: Boolean,
    ): KeyPairGenerator {
        val specBuilder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(RSA_KEY_SIZE)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)

        if (kind == DeviceKeyKind.MODERN) {
            if (Build.VERSION.SDK_INT >= 35) {
                specBuilder
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setMgf1Digests(KeyProperties.DIGEST_SHA256)
            } else {
                error("Modern Android Keystore identity requires API 35")
            }
        } else {
            specBuilder
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(useStrongBox)
        }

        return KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE,
        ).apply {
            initialize(specBuilder.build())
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LEGACY_KEY_ALIAS = "levik_device_identity_v2"
        private const val MODERN_KEY_ALIAS = "levik_device_identity_v3"
        private const val RSA_KEY_SIZE = 3072
        private const val RSA_OAEP_SHA_256_TRANSFORMATION =
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val RSA_OAEP_SHA_1_TRANSFORMATION =
            "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
        private const val SHA_256_WITH_RSA_PSS = "SHA256withRSA/PSS"
        private const val SHA_256_WITH_RSA = "SHA256withRSA"
        private val KEY_CAPABILITY_PROBE =
            "levik-device-key-capability-v1".encodeToByteArray()
        const val SIGNING_PS256 = "PS256"
        const val SIGNING_RS256 = "RS256"
        const val PROFILE_ENCRYPTION_OAEP_256 = "RSA-OAEP-256+A256GCM"
        const val PROFILE_ENCRYPTION_OAEP = "RSA-OAEP+A256GCM"

        private val PSS_SHA_256 = PSSParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            32,
            1,
        )
        private val OAEP_SHA_256 = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT,
        )
        private val OAEP_SHA_1 = OAEPParameterSpec(
            "SHA-1",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT,
        )

        fun selectKeyKind(
            sdkInt: Int,
            modernKeyExists: Boolean,
            legacyKeyExists: Boolean,
        ): DeviceKeyKind = when {
            modernKeyExists -> DeviceKeyKind.MODERN
            legacyKeyExists -> DeviceKeyKind.LEGACY
            sdkInt >= 35 -> DeviceKeyKind.MODERN
            else -> DeviceKeyKind.LEGACY
        }

        fun capabilityFor(kind: DeviceKeyKind): AlgorithmCapability =
            if (kind == DeviceKeyKind.MODERN) {
                AlgorithmCapability(
                    requestSigningAlgorithm = SIGNING_PS256,
                    profileEncryptionAlgorithm = PROFILE_ENCRYPTION_OAEP_256,
                )
            } else {
                AlgorithmCapability(
                    requestSigningAlgorithm = SIGNING_RS256,
                    profileEncryptionAlgorithm = PROFILE_ENCRYPTION_OAEP,
                )
            }

        fun canFallbackToLegacy(kind: DeviceKeyKind, error: Throwable): Boolean =
            kind == DeviceKeyKind.MODERN && (
                error is UnsupportedOperationException ||
                    error is ProviderException ||
                    error is GeneralSecurityException
                )

        fun base64Url(value: ByteArray): String =
            Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun decodeBase64Url(value: String): ByteArray =
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun sha256Hex(value: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

enum class DeviceKeyKind {
    LEGACY,
    MODERN,
}

private data class SelectedDeviceKey(
    val alias: String,
    val capability: AlgorithmCapability,
)

data class AlgorithmCapability(
    val requestSigningAlgorithm: String,
    val profileEncryptionAlgorithm: String,
)
