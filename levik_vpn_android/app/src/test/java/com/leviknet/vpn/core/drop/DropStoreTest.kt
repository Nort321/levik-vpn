package com.leviknet.vpn.core.drop

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropStoreTest {
    private val ids = listOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")

    @Test
    fun transfersSurviveARestart() = runBlocking {
        val folder = Files.createTempDirectory("levikdrop-store").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = folder.resolve("state.json")
            val store = DropStore(file, scope)
            store.load()
            store.folder = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
            store.put(StoredSender(ids[0], "dev-1", listOf(SourceFile("content://x/1", "отчёт \"1\".pdf", 10, 0), SourceFile("content://x/2", "b", 0, 1700000000000))))
            store.put(
                StoredReceiver(
                    ids[1], "dev-2",
                    manifest = listOf(ManifestFile("photo.jpg", 5)),
                    folder = "downloads",
                    targets = listOf(TargetFile("photo.jpg", 5, "content://media/1", null)),
                    decision = "accepted",
                ),
            )
            store.put(StoredReceiver(ids[2], "dev-3", finished = true))
            store.save()
            store.flush()

            val again = DropStore(file, scope)
            again.load()
            assertEquals(store.folder, again.folder)
            assertEquals(listOf(ids[0], ids[1], ids[2]), again.transfers.map { it.id })
            val sender = again.find(ids[0]) as StoredSender
            assertEquals("отчёт \"1\".pdf", sender.files[0].name)
            assertEquals(1700000000000, sender.files[1].modifiedMs)
            val receiver = again.find(ids[1]) as StoredReceiver
            assertEquals(listOf(ManifestFile("photo.jpg", 5)), receiver.manifest)
            assertEquals("content://media/1", receiver.targets?.single()?.partial)
            assertNull(receiver.targets?.single()?.final)
            assertEquals("accepted", receiver.decision)
            assertTrue(again.find(ids[2])!!.finished)

            // A broken file starts empty instead of stopping LevikDrop.
            file.writeText("{not json")
            val broken = DropStore(file, scope)
            broken.load()
            assertEquals(emptyList<StoredTransfer>(), broken.transfers)
        } finally {
            scope.cancel()
            folder.deleteRecursively()
        }
    }

    @Test
    fun pinsRememberKeysPerDevice() {
        val saved = mutableMapOf<String, ByteArray>()
        val secrets = object : DropSecrets {
            override fun get(name: String): ByteArray? = saved[name]

            override fun put(name: String, value: ByteArray) {
                saved[name] = value
            }
        }
        val pins = DropPins(secrets)
        pins.load()
        val key = byteArrayOf(1, 2, 3)
        assertEquals(Trust.NEW, pins.trust("dev", key))
        pins.pin("dev", key)
        val reloaded = DropPins(secrets).also(DropPins::load)
        assertEquals(Trust.TRUSTED, reloaded.trust("dev", key))
        assertEquals(Trust.CHANGED, reloaded.trust("dev", byteArrayOf(9)))
        assertEquals(Trust.NEW, reloaded.trust("other", key))
    }

    @Test
    fun devicesSkipThisPhoneAndUnknownPlatforms() {
        val devices = DropEngine.parseDevices(
            Json.parseToJsonElement(
                """
                [
                  {"deviceId":"me","name":"Pixel","platform":"android","ready":true,"keyId":"k","current":true},
                  {"deviceId":"pc","name":"Домашний ПК","platform":"windows","ready":true,"keyId":"k"},
                  {"deviceId":"mac","name":"MacBook","platform":"macos","ready":false},
                  {"deviceId":"tv","name":"TV","platform":"tizen","ready":true,"keyId":"k"},
                  {"name":"no id","platform":"linux"}
                ]
                """,
            ),
        )
        assertEquals(
            listOf(
                DropDevice("pc", "Домашний ПК", "windows", ready = true, supported = true),
                DropDevice("mac", "MacBook", "macos", ready = false, supported = false),
            ),
            devices,
        )
    }
}
