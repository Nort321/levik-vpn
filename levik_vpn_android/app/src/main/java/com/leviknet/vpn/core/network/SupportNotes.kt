package com.leviknet.vpn.core.network

import com.leviknet.vpn.BuildConfig
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A one-time Levik Notes message, encrypted on the device. The server stores
 * only the ciphertext; the key travels in the link fragment, which browsers
 * never send. Mirrors lib/notes/crypto.ts in the site.
 */
internal data class EncryptedSupportNote(
    val id: String,
    val keyFragment: String,
    val keyCommitment: String,
    val iv: String,
    val ciphertext: String,
)

private val base64Url = Base64.getUrlEncoder().withoutPadding()

internal fun encryptSupportNote(plaintext: String, random: SecureRandom = SecureRandom()): EncryptedSupportNote {
    val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
    require(plaintextBytes.isNotEmpty() && plaintextBytes.size <= MAX_SUPPORT_NOTE_BYTES) {
        "Support note must be 1..$MAX_SUPPORT_NOTE_BYTES bytes"
    }
    val id = base64Url.encodeToString(ByteArray(16).also(random::nextBytes))
    val key = ByteArray(32).also(random::nextBytes)
    val iv = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(supportNoteAad(id))
    val commitment = MessageDigest.getInstance("SHA-256").run {
        update("levik-notes:key:v1:$id:".toByteArray(StandardCharsets.UTF_8))
        digest(key)
    }
    return EncryptedSupportNote(
        id = id,
        keyFragment = "v1.${base64Url.encodeToString(key)}",
        keyCommitment = base64Url.encodeToString(commitment),
        iv = base64Url.encodeToString(iv),
        ciphertext = base64Url.encodeToString(cipher.doFinal(plaintextBytes)),
    )
}

internal fun supportNoteAad(id: String): ByteArray =
    "levik-notes:v1:$id".toByteArray(StandardCharsets.UTF_8)

internal fun supportNoteUrl(host: String, note: EncryptedSupportNote): String =
    "https://$host/${note.id}#${note.keyFragment}"

/** Posts encrypted notes straight to the note site, trying each of its domains. */
internal class SupportNoteClient(
    private val hosts: List<String> = NOTE_HOSTS,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun create(plaintext: String, expiresInDays: Int = 7): String = withContext(Dispatchers.IO) {
        val note = encryptSupportNote(plaintext)
        val body = json.encodeToString(
            CreateNoteRequest(
                id = note.id,
                keyCommitment = note.keyCommitment,
                iv = note.iv,
                ciphertext = note.ciphertext,
                expiresInDays = expiresInDays,
            ),
        ).toByteArray(StandardCharsets.UTF_8)
        var failure: Exception? = null
        for (host in hosts) {
            try {
                post(host, body)
                return@withContext supportNoteUrl(host, note)
            } catch (error: IOException) {
                failure = error
            }
        }
        throw failure ?: IOException("No note host configured")
    }

    private fun post(host: String, body: ByteArray) {
        val connection = URL("https://$host/api/notes").openConnection() as HttpsURLConnection
        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LevikVPN-Android/${BuildConfig.VERSION_NAME}")
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status == 201) return
            if (status >= 500) throw IOException("Note host answered HTTP $status")
            val stream = connection.errorStream ?: throw IOException("Note host answered HTTP $status")
            val message = stream.use { input ->
                runCatching {
                    json.decodeFromString<CreateNoteResponse>(String(input.readNBytesCompat(4_096), StandardCharsets.UTF_8)).message
                }.getOrNull()
            }
            // Rejections such as rate limits are final; outages try the next domain.
            throw IllegalStateException(message ?: "Note host answered HTTP $status")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val NOTE_HOSTS = listOf("note.leviknet.com", "note.leviknet.org")
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var size = 0
    while (size < limit) {
        val count = read(buffer, size, limit - size)
        if (count < 0) break
        size += count
    }
    return buffer.copyOf(size)
}
