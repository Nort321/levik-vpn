package com.leviknet.vpn.core.drop

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.Signature
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropEngineTest {
    private val oldId = "0b9f3c1e-5d2a-4c6b-9e8f-1a2b3c4d5e6f"
    private val newId = "1b9f3c1e-5d2a-4c6b-9e8f-1a2b3c4d5e6f"
    private val empty = Json.parseToJsonElement("""{"transfers":[]}""").jsonObject

    private open inner class Api : DropApi {
        override suspend fun presence(key: String) = Unit
        override suspend fun clearPresence() = Unit
        override suspend fun devices() = Json.parseToJsonElement("""{"available":true,"devices":[]}""").jsonObject
        override suspend fun transfers() = empty
        override suspend fun create(receiverDeviceId: String, fileCount: Int, totalBytes: Long): JsonObject = error("unexpected create")
        override suspend fun update(id: String, action: String, reason: String?): JsonObject = error("unexpected update")
    }

    private fun transfer(id: String, state: String = "done") = """{
        "id":"$id","role":"sender","peerDeviceId":"peer","peerName":"Peer",
        "peerPlatform":"windows","state":"$state","fileCount":1,"totalBytes":3,
        "createdAt":"2026-10-25T00:00:00Z"
    }"""

    private fun engine(api: DropApi, file: java.io.File, dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        joiner: RelayJoiner = RelayJoiner { _, _, _ -> error("stale transfer started a relay") },
    ): DropEngine {
        val pair = DropWire.ephemeralKey()
        return DropEngine(
            api, object : DropSecrets {
                override fun get(name: String): ByteArray? = null
                override fun put(name: String, value: ByteArray) = Unit
            }, {
                object : DropIdentity {
                    override val spki = pair.public.encoded
                    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                        initSign(pair.private); update(payload); sign()
                    }
                }
            }, file,
            object : DropSources {
                override suspend fun describe(refs: List<String>) = refs.map { SourceFile(it, "file.txt", 3, 0) }
                override fun stat(file: SourceFile) = SourceStat(3, 0)
                override fun open(file: SourceFile) = ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }, object : DropFolders {
                override val default: String? = null
                override fun open(folder: String): DropTargets = error("unexpected folder")
            }, { null }, {}, joiner = joiner, dispatcher = dispatcher,
        )
    }

    @Test
    fun `receiver exposes the matching verification code before sender releases manifest`() = runBlocking {
        val directory = Files.createTempDirectory("drop-code-hint").toFile()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sockets = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            client to listener.accept()
        }
        val senderChannel = FrameChannel(sockets.first, scope)
        val receiverChannel = FrameChannel(sockets.second, scope)
        val api = object : Api() {
            override suspend fun transfers() = Json.parseToJsonElement("""{"transfers":[${transfer(oldId, "offered").replace("\"role\":\"sender\"", "\"role\":\"receiver\"")}]}""").jsonObject
            override suspend fun update(id: String, action: String, reason: String?) = Json.parseToJsonElement("""{
                "connection":{"token":"test","endpoints":[{"host":"127.0.0.1","port":443,"pin":"${"a".repeat(43)}"}]}
            }""").jsonObject
        }
        val engine = engine(api, directory.resolve("state.json"), dispatcher, RelayJoiner { _, _, _ -> receiverChannel })
        val code = CompletableDeferred<String>()
        val confirmation = CompletableDeferred<Boolean>()
        val pair = DropWire.ephemeralKey()
        val identity = object : DropIdentity {
            override val spki = pair.public.encoded
            override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private); update(payload); sign()
            }
        }
        val sources = object : DropSources {
            override suspend fun describe(refs: List<String>) = listOf(SourceFile("source", "file.txt", 3, 0))
            override fun stat(file: SourceFile) = SourceStat(3, 0)
            override fun open(file: SourceFile) = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        val sender = DropSession(senderChannel, SenderWork(oldId, identity, "windows", "android", { Trust.NEW }, false,
            SessionEvents({ value, _, _ -> code.complete(value) }, {}, {}), { _, _ -> confirmation.await() },
            sources.describe(emptyList()), sources), scope)
        try {
            scope.launch { try { while (true) sender.inbox.send(senderChannel.next()) } catch (_: Exception) { sender.abort("closed") } }
            engine.start()
            engine.setOnline(true)
            senderChannel.send(byteArrayOf(0) + """{"t":"peer"}""".encodeToByteArray())
            scope.launch { sender.run() }
            val expected = withTimeout(5_000) { code.await() }
            withTimeout(5_000) { while (engine.snapshot.value.transfers.firstOrNull()?.verificationCode == null) delay(10) }
            val incoming = engine.snapshot.value.transfers.single()
            assertEquals(expected, incoming.verificationCode)
            assertNull(incoming.files)
            assertNull(incoming.prompt)
        } finally {
            confirmation.complete(false)
            engine.signedOut()
            scope.cancel()
            sockets.first.close()
            sockets.second.close()
            dispatcher.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `late refresh cannot restore previous owner transfers after signout and restart`() = runBlocking {
        val directory = Files.createTempDirectory("drop-epoch").toFile()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<JsonObject>()
        val returned = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val api = object : Api() {
            override suspend fun transfers(): JsonObject {
                if (calls.incrementAndGet() != 1) return Json.parseToJsonElement("""{"transfers":[${transfer(newId)}]}""").jsonObject
                return withContext(NonCancellable) {
                    entered.complete(Unit)
                    oldResponse.await().also { returned.complete(Unit) }
                }
            }
        }
        val engine = engine(api, directory.resolve("state.json"), dispatcher)
        try {
            engine.start()
            withTimeout(5_000) { entered.await() }
            engine.signedOut()
            assertTrue(engine.snapshot.value.transfers.isEmpty())
            engine.start()
            withTimeout(5_000) { while (engine.snapshot.value.transfers.none { it.id == newId }) delay(10) }
            oldResponse.complete(Json.parseToJsonElement("""{"transfers":[${transfer(oldId)}]}""").jsonObject)
            withTimeout(5_000) { returned.await() }
            delay(400)
            assertEquals(listOf(newId), engine.snapshot.value.transfers.map { it.id })
        } finally {
            oldResponse.complete(empty)
            engine.signedOut()
            dispatcher.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `late create cannot restore a transfer or start a relay after signout`() = runBlocking {
        val directory = Files.createTempDirectory("drop-create-epoch").toFile()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JsonObject>()
        val api = object : Api() {
            override suspend fun create(receiverDeviceId: String, fileCount: Int, totalBytes: Long): JsonObject = withContext(NonCancellable) {
                entered.complete(Unit)
                response.await()
            }
        }
        val engine = engine(api, directory.resolve("state.json"), dispatcher)
        try {
            engine.start()
            withTimeout(5_000) { while (engine.snapshot.value.loadedAtMs == null) delay(10) }
            val send = async { runCatching { engine.send("peer", listOf("source")) } }
            withTimeout(5_000) { entered.await() }
            engine.signedOut()
            response.complete(Json.parseToJsonElement("""{"transfer":${transfer(oldId, "offered")}}""").jsonObject)
            assertTrue(withTimeout(5_000) { send.await() }.isFailure)
            delay(300)
            assertTrue(engine.snapshot.value.transfers.isEmpty())
        } finally {
            response.complete(empty)
            engine.signedOut()
            dispatcher.close()
            directory.deleteRecursively()
        }
    }
}
