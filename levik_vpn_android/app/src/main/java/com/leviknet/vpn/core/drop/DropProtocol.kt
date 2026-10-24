package com.leviknet.vpn.core.drop

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * LevikDrop v1 wire format, docs/levik-drop.md "Session". The desktop apps
 * implement the same bytes; DropProtocolTest checks the shared vectors.
 */

class DropProtocolException(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw DropProtocolException(message)

object DropWire {
    const val PROTOCOL = 1
    const val MAX_FRAME = 270_336
    const val MAX_CHUNK = 262_144
    const val MAX_FILES = 20
    const val MAX_TOTAL = 2L * 1024 * 1024 * 1024

    /** Bytes the sender keeps in flight beyond the last acknowledgement. */
    const val WINDOW = 8L * 1024 * 1024
    const val ACK_EVERY = 1024L * 1024
    val KEEPALIVE = byteArrayOf(0x7f)

    private const val COMMIT = "levik-drop/v1 commit\u0000"
    private const val TRANSCRIPT = "levik-drop/v1 transcript\u0000"
    private const val SENDER = "levik-drop/v1 sender\u0000"
    private const val RECEIVER = "levik-drop/v1 receiver\u0000"
    private const val KEYS = "levik-drop/v1 keys"
    private const val CODE = "levik-drop/v1 code"
    private const val LAN_DIALER = "levik-drop/v1 lan dialer"
    private const val LAN_LISTENER = "levik-drop/v1 lan listener"

    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun lp(value: ByteArray): ByteArray {
        if (value.size > 0xffff) fail("field too long")
        return byteArrayOf((value.size ushr 8).toByte(), value.size.toByte()) + value
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    fun hmac(key: ByteArray, label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(label.encodeToByteArray())
    }

    fun transferBytes(id: String): ByteArray {
        val lower = id.lowercase()
        if (!UUID.matches(lower)) fail("bad transfer id")
        return lower.replace("-", "").hexToBytes()
    }

    fun isTransferId(id: String): Boolean = UUID.matches(id)

    fun keyHash(spki: ByteArray): String = sha256(spki).toHex()

    /** The peer's identity key: ECDSA P-256, SPKI DER. */
    fun identityPublicKey(spki: ByteArray): PublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        } catch (_: Exception) {
            fail("bad identity key")
        }
        if (key !is ECPublicKey || !isP256(key.params)) fail("identity key is not P-256")
        return key
    }

    // Handshake ---------------------------------------------------------------------------

    class SenderHello(val transfer: ByteArray, val identity: ByteArray, val ephemeral: ByteArray, val commit: ByteArray)

    class ReceiverHello(val transfer: ByteArray, val identity: ByteArray, val ephemeral: ByteArray, val nonce: ByteArray)

    fun commitment(identity: ByteArray, ephemeral: ByteArray, nonce: ByteArray): ByteArray =
        sha256(COMMIT.encodeToByteArray(), lp(identity), ephemeral, nonce)

    fun encodeSenderHello(hello: SenderHello): ByteArray =
        byteArrayOf(0x01, PROTOCOL.toByte()) + hello.transfer + lp(hello.identity) + hello.ephemeral + hello.commit

    fun encodeReceiverHello(hello: ReceiverHello): ByteArray =
        byteArrayOf(0x02, PROTOCOL.toByte()) + hello.transfer + lp(hello.identity) + hello.ephemeral + hello.nonce

    fun encodeSenderProof(nonce: ByteArray, signature: ByteArray): ByteArray = byteArrayOf(0x03) + nonce + lp(signature)

    fun encodeReceiverProof(signature: ByteArray): ByteArray = byteArrayOf(0x04) + lp(signature)

    private class Reader(private val data: ByteArray) {
        private var offset = 0

        fun take(length: Int): ByteArray {
            if (offset + length > data.size) fail("message too short")
            return data.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun byte(): Int = take(1)[0].toInt() and 0xff

        fun field(max: Int): ByteArray {
            val head = take(2)
            val length = ((head[0].toInt() and 0xff) shl 8) or (head[1].toInt() and 0xff)
            if (length < 1 || length > max) fail("bad field length")
            return take(length)
        }

        fun end() {
            if (offset != data.size) fail("trailing bytes")
        }
    }

    private class Hello(val transfer: ByteArray, val identity: ByteArray, val ephemeral: ByteArray, val tail: ByteArray)

    private fun hello(data: ByteArray, type: Int): Hello {
        val reader = Reader(data)
        if (reader.byte() != type) fail("unexpected message")
        if (reader.byte() != PROTOCOL) fail("unsupported protocol")
        val transfer = reader.take(16)
        val identity = reader.field(512)
        val ephemeral = reader.take(65)
        if (ephemeral[0] != 0x04.toByte()) fail("ephemeral key not uncompressed")
        val tail = reader.take(32)
        reader.end()
        return Hello(transfer, identity, ephemeral, tail)
    }

    fun decodeSenderHello(data: ByteArray): SenderHello =
        hello(data, 0x01).let { SenderHello(it.transfer, it.identity, it.ephemeral, it.tail) }

    fun decodeReceiverHello(data: ByteArray): ReceiverHello =
        hello(data, 0x02).let { ReceiverHello(it.transfer, it.identity, it.ephemeral, it.tail) }

    fun decodeSenderProof(data: ByteArray): Pair<ByteArray, ByteArray> {
        val reader = Reader(data)
        if (reader.byte() != 0x03) fail("unexpected message")
        val nonce = reader.take(32)
        val signature = reader.field(80)
        reader.end()
        return nonce to signature
    }

    fun decodeReceiverProof(data: ByteArray): ByteArray {
        val reader = Reader(data)
        if (reader.byte() != 0x04) fail("unexpected message")
        val signature = reader.field(80)
        reader.end()
        return signature
    }

    fun transcript(sender: SenderHello, receiver: ReceiverHello, senderNonce: ByteArray): ByteArray = sha256(
        TRANSCRIPT.encodeToByteArray(),
        sender.transfer,
        lp(sender.identity),
        sender.ephemeral,
        sender.commit,
        lp(receiver.identity),
        receiver.ephemeral,
        receiver.nonce,
        senderNonce,
    )

    fun signedPayload(sender: Boolean, th: ByteArray): ByteArray = (if (sender) SENDER else RECEIVER).encodeToByteArray() + th

    fun verifyTranscript(sender: Boolean, identity: ByteArray, th: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(identityPublicKey(identity))
            update(signedPayload(sender, th))
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    class SessionKeys(
        /** Sender → receiver records. */
        val sr: ByteArray,
        /** Receiver → sender records. */
        val rs: ByteArray,
        val lan: ByteArray,
        /** Six digits both screens show, "123 456". */
        val code: String,
    )

    /** A fresh P-256 key pair for one session. */
    fun ephemeralKey(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** The uncompressed point, 65 bytes. */
    fun encodePoint(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + unsigned32(key.w.affineX) + unsigned32(key.w.affineY)

    fun sessionKeys(own: KeyPair, peerEphemeral: ByteArray, th: ByteArray): SessionKeys {
        val params = (own.public as ECPublicKey).params
        val peer = decodePoint(peerEphemeral, params)
        val shared = try {
            KeyAgreement.getInstance("ECDH").run {
                init(own.private)
                doPhase(peer, true)
                generateSecret()
            }
        } catch (_: Exception) {
            fail("bad ephemeral key")
        }
        val okm = hkdf(shared, th, KEYS.encodeToByteArray(), 96)
        shared.fill(0)
        return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), okm.copyOfRange(64, 96), verificationCode(th))
    }

    fun verificationCode(th: ByteArray): String {
        val mac = hmac(th, CODE)
        val value = ByteBuffer.wrap(mac, 0, 4).int.toLong() and 0xffffffffL
        val digits = (value % 1_000_000).toString().padStart(6, '0')
        return "${digits.substring(0, 3)} ${digits.substring(3)}"
    }

    fun lanDialerFrame(transfer: ByteArray, key: ByteArray): ByteArray = byteArrayOf(0x20) + transfer + hmac(key, LAN_DIALER)

    fun lanListenerFrame(key: ByteArray): ByteArray = byteArrayOf(0x21) + hmac(key, LAN_LISTENER)

    fun sameBytes(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - filled)
            previous.copyInto(output, filled, 0, take)
            filled += take
            counter++
        }
        prk.fill(0)
        return output
    }

    private val P256_ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    private fun isP256(params: ECParameterSpec): Boolean = params.order == P256_ORDER && params.cofactor == 1

    private fun unsigned32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> fail("bad point")
        }
    }

    /** A point the peer sent: on the curve, or the session ends. */
    private fun decodePoint(encoded: ByteArray, params: ECParameterSpec): PublicKey {
        if (encoded.size != 65 || encoded[0] != 0x04.toByte()) fail("bad ephemeral key")
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        val curve = params.curve
        val p = (curve.field as java.security.spec.ECFieldFp).p
        if (x >= p || y >= p) fail("bad ephemeral key")
        val right = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        if (y.pow(2).mod(p) != right) fail("bad ephemeral key")
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        } catch (_: Exception) {
            fail("bad ephemeral key")
        }
    }

    // Records -----------------------------------------------------------------------------

    private fun nonceFor(seq: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()

    private fun headerFor(seq: Long): ByteArray = ByteBuffer.allocate(9).put(0x10).putLong(seq).array()

    /** One direction of a session: counters start at 0 and grow by one. */
    class RecordSealer(key: ByteArray) {
        private val key = SecretKeySpec(key, "AES")
        private var seq = 0L

        fun seal(plaintext: ByteArray): ByteArray {
            val current = seq++
            val header = headerFor(current)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonceFor(current)))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plaintext)
        }
    }

    class RecordOpener(key: ByteArray) {
        private val key = SecretKeySpec(key, "AES")
        private var seq = 0L

        fun open(record: ByteArray): ByteArray {
            if (record.size < 9 + 16 + 1 || record[0] != 0x10.toByte()) fail("not a record")
            val current = ByteBuffer.wrap(record, 1, 8).long
            if (current != seq) fail("record out of order")
            val plaintext = try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonceFor(current)))
                    updateAAD(record, 0, 9)
                    doFinal(record, 9, record.size - 9)
                }
            } catch (_: Exception) {
                fail("record does not authenticate")
            }
            seq++
            return plaintext
        }
    }

    // Plaintext ---------------------------------------------------------------------------

    val FAILURES = listOf("source_changed", "integrity", "storage_full", "storage_error", "source_unreadable", "protocol", "key_rejected")

    fun encodeControl(control: DropControl): ByteArray = byteArrayOf(0x01) + control.toJson().toString().encodeToByteArray()

    fun encodeData(file: Int, offset: Long, bytes: ByteArray, length: Int = bytes.size): ByteArray {
        if (length < 1 || length > MAX_CHUNK) fail("bad chunk")
        val buffer = ByteBuffer.allocate(11 + length)
        buffer.put(0x02).putShort(file.toShort()).putLong(offset).put(bytes, 0, length)
        return buffer.array()
    }

    fun decodePlaintext(plaintext: ByteArray): DropPlaintext {
        if (plaintext.isEmpty()) return DropPlaintext.Unknown
        if (plaintext[0] == 0x02.toByte()) {
            if (plaintext.size < 12 || plaintext.size > 11 + MAX_CHUNK) fail("bad data record")
            val buffer = ByteBuffer.wrap(plaintext)
            buffer.get()
            val file = buffer.short.toInt() and 0xffff
            val offset = buffer.long
            if (offset < 0 || offset > MAX_TOTAL) fail("bad offset")
            return DropPlaintext.Data(file, offset, plaintext.copyOfRange(11, plaintext.size))
        }
        if (plaintext[0] != 0x01.toByte()) return DropPlaintext.Unknown
        if (plaintext.size > 64 * 1024) fail("control too long")
        val value = try {
            Json.parseToJsonElement(plaintext.decodeToString(1, plaintext.size, throwOnInvalidSequence = true))
        } catch (_: Exception) {
            fail("bad control")
        }
        return parseControl(value)?.let(DropPlaintext::Control) ?: DropPlaintext.Unknown
    }

    private val HEX = Regex("^[0-9a-f]{64}$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun isPrivateIpv4(address: String): Boolean {
        val match = IPV4.matchEntire(address) ?: return false
        val texts = match.groupValues.drop(1)
        if (texts.any { it.length > 1 && it.startsWith("0") }) return false
        val parts = texts.map(String::toInt)
        if (parts.any { it > 255 }) return false
        val (a, b) = parts
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun count(value: JsonElement?, max: Long): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val number = primitive.longOrNull ?: primitive.content.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it <= 9.007199254740991E15 }?.toLong()
        return number?.takeIf { it in 0..max }
    }

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A control the peer sent, checked field by field; null for a type this version does not know. */
    fun parseControl(value: JsonElement): DropControl? {
        val obj = value as? JsonObject ?: fail("bad control")
        val type = string(obj["t"]) ?: fail("bad control")
        return when (type) {
            "manifest" -> {
                val files = obj["files"] as? JsonArray ?: fail("bad manifest")
                if (files.size < 1 || files.size > MAX_FILES) fail("bad manifest")
                val parsed = files.map { item ->
                    val file = item as? JsonObject ?: fail("bad manifest")
                    val name = string(file["name"]) ?: fail("bad manifest")
                    // JavaScript counts UTF-16 units: the same limit on both ends.
                    if (name.isEmpty() || name.length > 1024) fail("bad manifest")
                    ManifestFile(name, count(file["size"], MAX_TOTAL) ?: fail("bad manifest"))
                }
                val total = parsed.sumOf(ManifestFile::size)
                if (count(obj["total"], Long.MAX_VALUE) != total || total > MAX_TOTAL) fail("bad manifest")
                DropControl.Manifest(parsed)
            }
            "accept" -> {
                val offsets = obj["offsets"] as? JsonArray ?: fail("bad accept")
                val prefix = obj["prefix"] as? JsonArray ?: fail("bad accept")
                if (offsets.size != prefix.size || offsets.size > MAX_FILES) fail("bad accept")
                DropControl.Accept(
                    offsets.map { count(it, MAX_TOTAL) ?: fail("bad accept") },
                    prefix.map { item ->
                        if (item is JsonNull) null else string(item)?.takeIf(HEX::matches) ?: fail("bad accept")
                    },
                )
            }
            "ack" -> DropControl.Ack(
                count(obj["file"], (MAX_FILES - 1).toLong())?.toInt() ?: fail("bad ack"),
                count(obj["offset"], MAX_TOTAL) ?: fail("bad ack"),
            )
            "end" -> DropControl.End(
                count(obj["file"], (MAX_FILES - 1).toLong())?.toInt() ?: fail("bad end"),
                string(obj["sha256"])?.takeIf(HEX::matches) ?: fail("bad end"),
            )
            "file_ok" -> DropControl.FileOk(count(obj["file"], (MAX_FILES - 1).toLong())?.toInt() ?: fail("bad file_ok"))
            "fail" -> DropControl.Fail(string(obj["reason"])?.takeIf(FAILURES::contains) ?: "protocol")
            "lan" -> {
                val addrs = obj["addrs"] as? JsonArray ?: fail("bad lan")
                val port = count(obj["port"], 65_535) ?: fail("bad lan")
                if (addrs.size < 1 || addrs.size > 8 || port < 1) fail("bad lan")
                DropControl.Lan(addrs.map { item -> string(item)?.takeIf(::isPrivateIpv4) ?: fail("bad lan") }, port.toInt())
            }
            "decline" -> DropControl.Decline
            "done" -> DropControl.Done
            "pause" -> DropControl.Pause
            "cancel" -> DropControl.Cancel
            "lan_on" -> DropControl.LanOn
            "lan_ok" -> DropControl.LanOk
            else -> null
        }
    }
}

data class ManifestFile(val name: String, val size: Long)

sealed class DropControl {
    data class Manifest(val files: List<ManifestFile>) : DropControl() {
        val total: Long get() = files.sumOf(ManifestFile::size)
    }
    data class Accept(val offsets: List<Long>, val prefix: List<String?>) : DropControl()
    data object Decline : DropControl()
    data class Ack(val file: Int, val offset: Long) : DropControl()
    data class End(val file: Int, val sha256: String) : DropControl()
    data class FileOk(val file: Int) : DropControl()
    data object Done : DropControl()
    data object Pause : DropControl()
    data object Cancel : DropControl()
    data class Fail(val reason: String) : DropControl()
    data class Lan(val addrs: List<String>, val port: Int) : DropControl()
    data object LanOn : DropControl()
    data object LanOk : DropControl()

    /** Field order as the desktop apps write it. */
    fun toJson(): JsonObject {
        val fields: Map<String, JsonElement> = when (this) {
            is Manifest -> mapOf(
                "t" to JsonPrimitive("manifest"),
                "files" to JsonArray(files.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "size" to JsonPrimitive(it.size))) }),
                "total" to JsonPrimitive(total),
            )
            is Accept -> mapOf(
                "t" to JsonPrimitive("accept"),
                "offsets" to JsonArray(offsets.map(::JsonPrimitive)),
                "prefix" to JsonArray(prefix.map { it?.let(::JsonPrimitive) ?: JsonNull }),
            )
            is Ack -> mapOf("t" to JsonPrimitive("ack"), "file" to JsonPrimitive(file), "offset" to JsonPrimitive(offset))
            is End -> mapOf("t" to JsonPrimitive("end"), "file" to JsonPrimitive(file), "sha256" to JsonPrimitive(sha256))
            is FileOk -> mapOf("t" to JsonPrimitive("file_ok"), "file" to JsonPrimitive(file))
            is Fail -> mapOf("t" to JsonPrimitive("fail"), "reason" to JsonPrimitive(reason))
            is Lan -> mapOf("t" to JsonPrimitive("lan"), "addrs" to JsonArray(addrs.map(::JsonPrimitive)), "port" to JsonPrimitive(port))
            Decline -> mapOf("t" to JsonPrimitive("decline"))
            Done -> mapOf("t" to JsonPrimitive("done"))
            Pause -> mapOf("t" to JsonPrimitive("pause"))
            Cancel -> mapOf("t" to JsonPrimitive("cancel"))
            LanOn -> mapOf("t" to JsonPrimitive("lan_on"))
            LanOk -> mapOf("t" to JsonPrimitive("lan_ok"))
        }
        return JsonObject(fields)
    }
}

sealed class DropPlaintext {
    class Control(val control: DropControl) : DropPlaintext()
    class Data(val file: Int, val offset: Long, val bytes: ByteArray) : DropPlaintext()
    data object Unknown : DropPlaintext()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
