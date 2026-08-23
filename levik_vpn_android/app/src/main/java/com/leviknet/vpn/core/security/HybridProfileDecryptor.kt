package com.leviknet.vpn.core.security

import com.leviknet.vpn.core.network.TunnelProfileEnvelope
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HybridProfileDecryptor(
    private val deviceIdentity: DeviceIdentity,
) {
    fun decrypt(envelope: TunnelProfileEnvelope): ByteArray {
        require(envelope.algorithm in SUPPORTED_ALGORITHMS) {
            "Unsupported tunnel profile encryption"
        }
        val encryptedKey = DeviceIdentity.decodeBase64Url(envelope.encryptedKey)
        val iv = DeviceIdentity.decodeBase64Url(envelope.iv)
        val ciphertext = DeviceIdentity.decodeBase64Url(envelope.ciphertext)
        val aad = DeviceIdentity.decodeBase64Url(envelope.aad)

        require(encryptedKey.size in 256..512) { "Invalid encrypted key size" }
        require(iv.size == GCM_IV_BYTES) { "Invalid profile IV size" }
        require(ciphertext.size in GCM_TAG_BYTES..MAX_PROFILE_CIPHERTEXT_BYTES) {
            "Invalid encrypted profile size"
        }
        require(aad.size in 1..MAX_AAD_BYTES) { "Invalid profile AAD size" }

        val key = deviceIdentity.decryptProfileKey(envelope.algorithm, encryptedKey)
        require(key.size == AES_KEY_BYTES) { "Invalid decrypted profile key" }

        return try {
            Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, KeyPropertiesAlgorithm.AES),
                    GCMParameterSpec(GCM_TAG_BITS, iv),
                )
                updateAAD(aad)
                doFinal(ciphertext)
            }
        } finally {
            key.fill(0)
            encryptedKey.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
            aad.fill(0)
        }
    }

    private object KeyPropertiesAlgorithm {
        const val AES = "AES"
    }

    companion object {
        private val SUPPORTED_ALGORITHMS = setOf(
            DeviceIdentity.PROFILE_ENCRYPTION_OAEP_256,
            DeviceIdentity.PROFILE_ENCRYPTION_OAEP,
        )
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        private const val MAX_PROFILE_CIPHERTEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_AAD_BYTES = 2048
    }
}
