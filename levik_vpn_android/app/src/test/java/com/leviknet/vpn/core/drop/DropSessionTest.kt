package com.leviknet.vpn.core.drop

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyPair
import java.security.Signature
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Plain files in a folder, the way the phone's folders behave. */
private class FolderTargets(private val folder: File) : DropTargets {
    override fun freeBytes(): Long? = null

    override fun createPartial(name: String): String =
        File.createTempFile(".drop", ".levikdrop", folder).path

    override fun length(partial: String): Long? = File(partial).takeIf(File::exists)?.length()

    override fun openWrite(partial: String, offset: Long): TargetWriter {
        val file = RandomAccessFile(partial, "rw")
        file.setLength(offset)
        file.seek(offset)
        return object : TargetWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = file.write(bytes, offset, length)

            override fun sync() = file.fd.sync()

            override fun close() = file.close()
        }
    }

    override fun openRead(partial: String): InputStream = FileInputStream(partial)

    override fun place(partial: String, name: String): String {
        val target = generateSequence(0) { it + 1 }.map { File(folder, DropNames.numberedName(name, it)) }.first { !it.exists() }
        check(File(partial).renameTo(target))
        return target.path
    }

    override fun delete(partial: String) {
        File(partial).delete()
    }
}

private class FileSources : DropSources {
    override suspend fun describe(refs: List<String>): List<SourceFile> = refs.map { path ->
        val file = File(path)
        SourceFile(path, file.name, file.length(), file.lastModified())
    }

    override fun stat(file: SourceFile): SourceStat? = File(file.ref).takeIf(File::exists)?.let { SourceStat(it.length(), it.lastModified()) }

    override fun open(file: SourceFile): InputStream = FileInputStream(file.ref)
}

private class SoftwareIdentity(private val pair: KeyPair = DropWire.ephemeralKey()) : DropIdentity {
    override val spki: ByteArray = pair.public.encoded

    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(payload)
        sign()
    }
}

class DropSessionTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val transferId = "0b9f3c1e-5d2a-4c6b-9e8f-1a2b3c4d5e6f"
    private val events = SessionEvents(keyed = { _, _, _ -> }, path = {}, progress = {})

    @Before
    fun setUp() {
        root = Files.createTempDirectory("levikdrop").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun source(name: String, bytes: ByteArray): File = File(root, "out").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) }

    private val inbox: File get() = File(root, "in").apply { mkdirs() }

    /** Two ends of a relay connection: what one side sends reaches the other side's session. */
    private fun relayPair(): Pair<FrameChannel, FrameChannel> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val accepted = server.accept()
            return FrameChannel(client, scope) to FrameChannel(accepted, scope)
        }
    }

    private fun pump(from: FrameChannel, to: DropSession) = scope.launch {
        try {
            while (true) to.inbox.send(from.next())
        } catch (_: Exception) {
            to.abort("relay closed")
        }
    }

    private class Run(val sender: SessionOutcome, val receiver: SessionOutcome)

    private fun run(
        files: List<File>,
        decide: suspend (List<ManifestFile>, String, Trust) -> TargetFiles?,
        manifest: List<ManifestFile>? = null,
        targets: TargetFiles? = null,
        senderConfirms: Boolean = true,
    ): Run = runBlocking {
        val (senderEnd, receiverEnd) = relayPair()
        val codes = mutableListOf<String>()
        val sources = FileSources()
        val senderWork = SenderWork(
            transferId, SoftwareIdentity(), "android", "windows", { Trust.NEW }, false,
            SessionEvents(keyed = { code, _, _ -> synchronized(codes) { codes += code } }, path = {}, progress = {}),
            { _, _ -> senderConfirms },
            sources.describe(files.map(File::getPath)), sources,
        )
        val receiverWork = ReceiverWork(
            transferId, SoftwareIdentity(), "windows", "android", { Trust.NEW }, false,
            SessionEvents(keyed = { code, _, _ -> synchronized(codes) { codes += code } }, path = {}, progress = {}),
            { _, _ -> true }, manifest, targets, decide,
        )
        val sender = DropSession(senderEnd, senderWork, scope)
        val receiver = DropSession(receiverEnd, receiverWork, scope)
        // A frame one side sends arrives at the other end of the socket pair.
        pump(senderEnd, sender)
        pump(receiverEnd, receiver)
        withTimeout(30_000) {
            val sent = async { sender.run() }
            val received = async { receiver.run() }
            Run(sent.await(), received.await()).also {
                senderEnd.close()
                receiverEnd.close()
                assertEquals(2, codes.size)
                assertEquals("Both screens show the same code", codes[0], codes[1])
            }
        }
    }

    private fun accepting(placed: MutableList<TargetFile> = mutableListOf()): suspend (List<ManifestFile>, String, Trust) -> TargetFiles? = { manifest, _, _ ->
        val store = FolderTargets(inbox)
        TargetFiles(store, TargetFiles.plan(store, manifest)) { synchronized(placed) { placed += it } }
    }

    private fun leftovers(): List<String> = inbox.list().orEmpty().filter { it.endsWith(".levikdrop") }

    @Test
    fun sendsSeveralFilesThroughTheRelay() {
        val big = Random(1).nextBytes(3 * 1024 * 1024 + 17)
        val files = listOf(source("video.bin", big), source("отчёт.txt", "привет".encodeToByteArray()), source("empty", ByteArray(0)))
        val placed = mutableListOf<TargetFile>()
        val result = run(files, accepting(placed))
        assertEquals(SessionOutcome.Done, result.sender)
        assertEquals(SessionOutcome.Done, result.receiver)
        assertArrayEquals(big, File(inbox, "video.bin").readBytes())
        assertEquals("привет", File(inbox, "отчёт.txt").readText())
        assertEquals(0L, File(inbox, "empty").length())
        assertEquals(3, placed.size)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun keepsExistingNames() {
        File(inbox, "a.txt").writeText("old")
        val result = run(listOf(source("a.txt", "new".encodeToByteArray())), accepting())
        assertEquals(SessionOutcome.Done, result.receiver)
        assertEquals("old", File(inbox, "a.txt").readText())
        assertEquals("new", File(inbox, "a (1).txt").readText())
    }

    /** A later session goes on from the bytes the receiver kept. */
    private fun resumed(kept: ByteArray, file: File): Run = runBlocking {
        val store = FolderTargets(inbox)
        val manifest = listOf(ManifestFile(file.name, file.length()))
        val planned = TargetFiles.plan(store, manifest)
        File(planned[0].partial).writeBytes(kept)
        val targets = TargetFiles(store, planned) {}
        run(listOf(file), { _, _, _ -> fail("asked again"); null }, manifest, targets)
    }

    @Test
    fun resumesFromTheKeptPrefix() {
        val bytes = Random(2).nextBytes(2 * 1024 * 1024)
        val file = source("photo.jpg", bytes)
        val result = resumed(bytes.copyOf(700_001), file)
        assertEquals(SessionOutcome.Done, result.sender)
        assertEquals(SessionOutcome.Done, result.receiver)
        assertArrayEquals(bytes, File(inbox, "photo.jpg").readBytes())
    }

    @Test
    fun aCompletePartialStillGetsChecked() {
        val bytes = Random(3).nextBytes(100_000)
        val file = source("whole.bin", bytes)
        val result = resumed(bytes, file)
        assertEquals(SessionOutcome.Done, result.receiver)
        assertArrayEquals(bytes, File(inbox, "whole.bin").readBytes())
    }

    @Test
    fun aChangedSourceFailsInsteadOfMixingVersions() {
        val bytes = Random(4).nextBytes(1_000_000)
        val file = source("doc.pdf", bytes)
        val other = bytes.copyOf(400_000).also { it[10] = (it[10] + 1).toByte() }
        val result = resumed(other, file)
        assertEquals(SessionOutcome.Failed(bySelf = true, "source_changed"), result.sender)
        assertEquals(SessionOutcome.Failed(bySelf = false, "source_changed"), result.receiver)
        assertTrue(!File(inbox, "doc.pdf").exists())
    }

    @Test
    fun declineEndsBothSides() {
        val result = run(listOf(source("x", "x".encodeToByteArray())), { _, _, _ -> null })
        assertEquals(SessionOutcome.Declined(bySelf = false), result.sender)
        assertEquals(SessionOutcome.Declined(bySelf = true), result.receiver)
    }

    @Test
    fun differentCodesStopTheSender() {
        val result = run(listOf(source("x", "x".encodeToByteArray())), accepting(), senderConfirms = false)
        assertEquals(SessionOutcome.Failed(bySelf = true, "key_rejected"), result.sender)
        assertEquals(SessionOutcome.Failed(bySelf = false, "key_rejected"), result.receiver)
        assertEquals(emptyList<String>(), inbox.list().orEmpty().toList())
    }

    @Test
    fun lanNeedsTheSessionKey() = runBlocking {
        val transfer = DropWire.transferBytes(transferId)
        val key = Random(5).nextBytes(32)
        val listener = checkNotNull(LanListener.open(transfer, key, scope, listOf("127.0.0.1")))
        val port = listener.port
        assertNull(DropLan.dial(listOf("127.0.0.1"), port, transfer, Random(6).nextBytes(32), scope))
        val dialed = checkNotNull(DropLan.dial(listOf("127.0.0.1"), port, transfer, key, scope))
        val accepted = checkNotNull(withTimeout(5_000) { listener.accepted() })
        dialed.send("hi".encodeToByteArray())
        assertEquals("hi", withTimeout(5_000) { accepted.next() }.decodeToString())
        dialed.close()
        accepted.close()
        listener.close()
    }
}
