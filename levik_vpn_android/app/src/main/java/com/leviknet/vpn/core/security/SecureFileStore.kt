package com.leviknet.vpn.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureFileStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME).apply {
        check(isDirectory || mkdirs()) { "Unable to create secure storage directory" }
    }
    private val lock = Any()

    fun put(name: String, plaintext: ByteArray) {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Secure value is too large" }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
            updateAAD(storageAad(name))
        }
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) { "Invalid Android Keystore IV" }
        val encrypted = cipher.doFinal(plaintext)
        val atomicFile = AtomicFile(fileFor(name))

        synchronized(lock) {
            val output = atomicFile.startWrite()
            try {
                val stream = DataOutputStream(output)
                stream.writeInt(STORAGE_VERSION)
                stream.writeInt(iv.size)
                stream.write(iv)
                stream.writeInt(encrypted.size)
                stream.write(encrypted)
                stream.flush()
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            } finally {
                encrypted.fill(0)
                iv.fill(0)
            }
        }
    }

    fun get(name: String): ByteArray? {
        val file = fileFor(name)
        if (!file.isFile) return null

        return synchronized(lock) {
            AtomicFile(file).openRead().use { raw ->
                DataInputStream(raw).use { input ->
                    check(input.readInt() == STORAGE_VERSION) { "Unsupported secure storage version" }
                    val ivSize = input.readInt()
                    check(ivSize == GCM_IV_BYTES) { "Invalid secure storage IV" }
                    val iv = ByteArray(ivSize).also(input::readFully)
                    val encryptedSize = input.readInt()
                    check(encryptedSize in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
                        "Invalid secure storage ciphertext"
                    }
                    val encrypted = ByteArray(encryptedSize).also(input::readFully)
                    check(input.read() == -1) { "Unexpected secure storage data" }

                    try {
                        Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
                            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                            updateAAD(storageAad(name))
                            doFinal(encrypted)
                        }
                    } finally {
                        iv.fill(0)
                        encrypted.fill(0)
                    }
                }
            }
        }
    }

    fun contains(name: String): Boolean = fileFor(name).isFile

    fun remove(name: String) {
        synchronized(lock) {
            AtomicFile(fileFor(name)).delete()
        }
    }

    fun clearAppSecrets() {
        synchronized(lock) {
            directory.listFiles().orEmpty().forEach { file ->
                if (file.isFile) {
                    AtomicFile(file).delete()
                }
            }
        }
    }

    private fun fileFor(name: String): File {
        require(name.matches(VALID_NAME)) { "Invalid secure storage key" }
        val digest = DeviceIdentity.sha256Hex(name.encodeToByteArray())
        return File(directory, "$digest.bin")
    }

    private fun storageAad(name: String): ByteArray =
        "$STORAGE_AAD_PREFIX$name".encodeToByteArray()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        synchronized(SecureFileStore::class.java) {
            val secondStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (secondStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            return KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            ).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()
        }
    }

    companion object {
        const val SESSION_TOKEN = "session_token"
        const val PENDING_REVOCATION_TOKEN = "pending_revocation_token"
        const val TUNNEL_PROFILE = "tunnel_profile"
        const val SELECTED_SERVER = "selected_server"
        const val APP_DATA_DISCLOSURE_CONSENT = "app_data_disclosure_consent"
        const val VPN_DISCLOSURE_CONSENT = "vpn_disclosure_consent"

        private const val DIRECTORY_NAME = "levik_secure_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "levik_local_storage_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        private const val STORAGE_VERSION = 1
        private const val STORAGE_AAD_PREFIX = "levik-secure-file:v1:"
        private const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        private val VALID_NAME = Regex("[a-z0-9_]{1,64}")
    }
}
