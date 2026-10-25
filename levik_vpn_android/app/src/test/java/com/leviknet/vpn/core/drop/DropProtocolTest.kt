package com.leviknet.vpn.core.drop

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The shared vectors of the desktop apps (tests/fixtures/levikDropVectors.json there). */
class DropProtocolTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResource("levikdrop/vectors.json")).readText(),
    ) as JsonObject

    private fun hex(name: String): ByteArray = vectors.getValue(name).jsonPrimitive.content.hexToBytes()

    private fun text(name: String): String = vectors.getValue(name).jsonPrimitive.content

    private fun counting(from: Int): ByteArray = ByteArray(32) { (from + it).toByte() }

    private val params = (DropWire.ephemeralKey().public as ECPublicKey).params

    /** The key pair with private scalar [scalar]; its public point is [point] (65 bytes). */
    private fun pair(scalar: ByteArray, point: ByteArray): KeyPair {
        val factory = KeyFactory.getInstance("EC")
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        return KeyPair(
            factory.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)),
            factory.generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params)),
        )
    }

    @Test
    fun handshakeMatchesDesktopBytes() {
        val transfer = DropWire.transferBytes(text("transfer"))
        val sender = DropWire.decodeSenderHello(hex("helloS"))
        val receiver = DropWire.decodeReceiverHello(hex("helloR"))
        assertArrayEquals(hex("spkiS"), sender.identity)
        assertArrayEquals(hex("spkiR"), receiver.identity)
        assertArrayEquals(transfer, sender.transfer)
        assertArrayEquals(counting(0xc0), receiver.nonce)

        val nonce = counting(0xa0)
        assertArrayEquals(hex("commit"), DropWire.commitment(sender.identity, sender.ephemeral, nonce))
        assertArrayEquals(hex("helloS"), DropWire.encodeSenderHello(DropWire.SenderHello(transfer, sender.identity, sender.ephemeral, sender.commit)))
        assertArrayEquals(hex("helloR"), DropWire.encodeReceiverHello(DropWire.ReceiverHello(transfer, receiver.identity, receiver.ephemeral, receiver.nonce)))

        val th = DropWire.transcript(sender, receiver, nonce)
        assertArrayEquals(hex("th"), th)

        // Both ends derive the same keys from their own ephemeral key.
        val senderKeys = DropWire.sessionKeys(pair(counting(0x41), sender.ephemeral), receiver.ephemeral, th)
        val receiverKeys = DropWire.sessionKeys(pair(counting(0x61), receiver.ephemeral), sender.ephemeral, th)
        for (keys in listOf(senderKeys, receiverKeys)) {
            assertArrayEquals(hex("sr"), keys.sr)
            assertArrayEquals(hex("rs"), keys.rs)
            assertArrayEquals(hex("lan"), keys.lan)
            assertEquals(text("code"), keys.code)
        }
        assertArrayEquals(hex("dialer"), DropWire.lanDialerFrame(transfer, senderKeys.lan))
        assertArrayEquals(hex("listener"), DropWire.lanListenerFrame(senderKeys.lan))
    }

    @Test
    fun signaturesBindTheRole() {
        val sender = DropWire.decodeSenderHello(hex("helloS"))
        val identity = pair(counting(0x01), sender.identity.copyOfRange(sender.identity.size - 65, sender.identity.size))
        val th = hex("th")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private)
            update(DropWire.signedPayload(sender = true, th))
            sign()
        }
        assertTrue(DropWire.verifyTranscript(sender = true, hex("spkiS"), th, signature))
        assertFalse(DropWire.verifyTranscript(sender = false, hex("spkiS"), th, signature))
        assertFalse(DropWire.verifyTranscript(sender = true, hex("spkiR"), th, signature))
        assertFalse(DropWire.verifyTranscript(sender = true, hex("spkiS"), th.copyOf().also { it[0] = (it[0] + 1).toByte() }, signature))
    }

    @Test
    fun recordsMatchDesktopBytes() {
        val sealer = DropWire.RecordSealer(hex("sr"))
        sealer.seal(DropWire.encodeControl(DropControl.Pause))
        val done = DropWire.encodeControl(DropControl.Done)
        assertEquals("""{"t":"done"}""", done.decodeToString(1, done.size))
        assertArrayEquals(hex("record1"), sealer.seal(done))

        val opener = DropWire.RecordOpener(hex("sr"))
        val first = DropWire.RecordSealer(hex("sr")).seal(DropWire.encodeControl(DropControl.Pause))
        assertTrue((DropWire.decodePlaintext(opener.open(first)) as DropPlaintext.Control).control == DropControl.Pause)
        assertTrue((DropWire.decodePlaintext(opener.open(hex("record1"))) as DropPlaintext.Control).control == DropControl.Done)

        // Out of order, replayed or tampered records do not open.
        assertFails { DropWire.RecordOpener(hex("sr")).open(hex("record1")) }
        assertFails { opener.open(hex("record1")) }
        val tampered = hex("record1").also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFails { DropWire.RecordOpener(hex("sr")).also { it.open(first) }.open(tampered) }
        assertFails { DropWire.RecordOpener(hex("rs")).open(first) }
    }

    @Test
    fun dataRecordsMatchDesktopBytes() {
        val data = DropWire.encodeData(3, 262_144, "abc".encodeToByteArray())
        assertArrayEquals(hex("data"), data)
        val decoded = DropWire.decodePlaintext(data) as DropPlaintext.Data
        assertEquals(3, decoded.file)
        assertEquals(262_144L, decoded.offset)
        assertEquals("abc", decoded.bytes.decodeToString())
        assertFails { DropWire.encodeData(0, 0, ByteArray(0)) }
        assertFails { DropWire.encodeData(0, 0, ByteArray(DropWire.MAX_CHUNK + 1)) }
    }

    @Test
    fun namesMatchDesktop() {
        val cases = vectors.getValue("names") as JsonArray
        for (case in cases) {
            val (raw, safe) = (case as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(raw, safe, DropNames.safeFileName(raw))
        }
        assertEquals("photo (2).jpg", DropNames.numberedName("photo.jpg", 2))
        assertEquals(".a.txt.levikdrop", DropNames.partialName("a.txt"))
    }

    private fun control(json: String): DropControl? =
        (DropWire.decodePlaintext(byteArrayOf(0x01) + json.encodeToByteArray()) as? DropPlaintext.Control)?.control

    @Test
    fun controlsParseStrictly() {
        assertEquals(
            DropControl.Manifest(listOf(ManifestFile("a.txt", 3), ManifestFile("b", 0))),
            control("""{"t":"manifest","files":[{"name":"a.txt","size":3},{"name":"b","size":0}],"total":3}"""),
        )
        assertEquals(
            DropControl.Accept(listOf(0, 5), listOf(null, "a".repeat(64))),
            control("""{"t":"accept","offsets":[0,5],"prefix":[null,"${"a".repeat(64)}"]}"""),
        )
        assertEquals(DropControl.Ack(1, 1_048_576), control("""{"t":"ack","file":1,"offset":1048576}"""))
        assertEquals(DropControl.Fail("protocol"), control("""{"t":"fail","reason":"whatever"}"""))
        assertEquals(DropControl.Lan(listOf("192.168.1.5"), 40_000), control("""{"t":"lan","addrs":["192.168.1.5"],"port":40000}"""))
        assertNull(control("""{"t":"later"}"""))
        assertFails { control("""{"t":"manifest","files":[{"name":"a","size":3}],"total":4}""") }
        assertFails { control("""{"t":"ack","file":20,"offset":1}""") }
        assertFails { control("""{"t":"ack","file":0,"offset":-1}""") }
        assertFails { control("""{"t":"lan","addrs":["8.8.8.8"],"port":40000}""") }
        assertFails { control("""{"t":"lan","addrs":["127.0.0.1"],"port":40000}""") }
        assertFails { control("{") }

        // What the app writes reads back the same.
        val manifest = DropControl.Manifest(listOf(ManifestFile("отчёт.pdf", 10)))
        assertEquals("""{"t":"manifest","files":[{"name":"отчёт.pdf","size":10}],"total":10}""", manifest.toJson().toString())
        assertEquals(manifest, control(manifest.toJson().toString()))
    }

    @Test
    fun onlyPrivateIpv4CountsAsLan() {
        for (address in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.10")) assertTrue(address, DropWire.isPrivateIpv4(address))
        for (address in listOf("172.32.0.1", "8.8.8.8", "127.0.0.1", "192.168.01.1", "256.1.1.1", "fe80::1", "192.168.1")) {
            assertFalse(address, DropWire.isPrivateIpv4(address))
        }
    }

    @Test
    fun badEphemeralKeysAreRefused() {
        val own = DropWire.ephemeralKey()
        val offCurve = ByteArray(65).also { it[0] = 0x04; it[64] = 1 }
        assertFails { DropWire.sessionKeys(own, offCurve, ByteArray(32)) }
        assertFails { DropWire.sessionKeys(own, ByteArray(33), ByteArray(32)) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: DropProtocolException) {
            return
        }
        fail("expected DropProtocolException")
    }
}
