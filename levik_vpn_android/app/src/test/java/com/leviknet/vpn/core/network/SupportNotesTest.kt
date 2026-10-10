package com.leviknet.vpn.core.network

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupportNotesTest {
    /** Hands out 0, 1, 2, … so the note matches a vector made with the site's web crypto code. */
    private class CountingRandom : SecureRandom() {
        private var next = 0

        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) bytes[index] = (next++ and 0xff).toByte()
        }
    }

    @Test
    fun matchesTheNoteSiteFormat() {
        val note = encryptSupportNote("Отчёт 🔐\nline", CountingRandom())

        assertEquals(
            EncryptedSupportNote(
                id = "AAECAwQFBgcICQoLDA0ODw",
                keyFragment = "v1.EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8",
                keyCommitment = "1WwAKQVULENk7CnBRNXJ68BNiBFkYHDf0U-EBjKwleM",
                iv = "MDEyMzQ1Njc4OTo7",
                ciphertext = "Ro70CyG6R8fFpMivuq4IEAEnjPlWEXqxtM_-cBACbRJFve43",
            ),
            note,
        )
        assertEquals(
            "https://note.leviknet.com/AAECAwQFBgcICQoLDA0ODw#v1.EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8",
            supportNoteUrl("note.leviknet.com", note),
        )
    }

    @Test
    fun decryptsOnlyWithTheNoteIdBound() {
        val plaintext = "REPORT\n".repeat(1_000)
        val note = encryptSupportNote(plaintext)
        val decoder = Base64.getUrlDecoder()
        val key = decoder.decode(note.keyFragment.removePrefix("v1."))
        fun open(id: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decoder.decode(note.iv)))
            cipher.updateAAD(supportNoteAad(id))
            return String(cipher.doFinal(decoder.decode(note.ciphertext)), Charsets.UTF_8)
        }

        assertEquals(plaintext, open(note.id))
        assertThrows(Exception::class.java) { open("AAAAAAAAAAAAAAAAAAAAAA") }
    }

    @Test
    fun refusesNotesTheSiteWouldReject() {
        assertThrows(IllegalArgumentException::class.java) { encryptSupportNote("") }
        assertThrows(IllegalArgumentException::class.java) {
            encryptSupportNote("x".repeat(MAX_SUPPORT_NOTE_BYTES + 1))
        }
    }
}
