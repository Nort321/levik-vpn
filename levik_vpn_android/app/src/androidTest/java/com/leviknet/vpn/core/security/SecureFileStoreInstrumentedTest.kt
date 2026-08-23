package com.leviknet.vpn.core.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureFileStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = SecureFileStore(context)

    @Test
    fun encryptedValueRoundTripsAndCanBeRemoved() {
        val name = "instrumentation_roundtrip"
        val value = "device-bound secret".encodeToByteArray()
        store.remove(name)

        store.put(name, value)

        assertTrue(store.contains(name))
        assertArrayEquals(value, store.get(name))
        store.remove(name)
        assertFalse(store.contains(name))
        assertNull(store.get(name))
    }

    @Test
    fun ciphertextTamperingIsRejected() {
        val name = "instrumentation_tamper"
        store.remove(name)
        store.put(name, "authenticated data".encodeToByteArray())
        val backingFile = File(
            File(context.noBackupFilesDir, "levik_secure_v1"),
            "${DeviceIdentity.sha256Hex(name.encodeToByteArray())}.bin",
        )
        RandomAccessFile(backingFile, "rw").use { file ->
            val lastOffset = file.length() - 1
            file.seek(lastOffset)
            val original = file.readByte().toInt()
            file.seek(lastOffset)
            file.writeByte(original xor 0x01)
        }

        val rejected = runCatching { store.get(name) }.isFailure

        assertTrue("AES-GCM must reject modified ciphertext", rejected)
        store.remove(name)
    }
}
