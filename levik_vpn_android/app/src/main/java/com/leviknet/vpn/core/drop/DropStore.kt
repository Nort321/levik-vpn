package com.leviknet.vpn.core.drop

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What survives a restart: the files of open transfers, partial entries, the
 * receive folder. The identity key lives in the Android Keystore, pins in
 * the app's encrypted storage.
 */

sealed class StoredTransfer {
    abstract val id: String
    abstract val peerDeviceId: String

    /** Set when the transfer ended; kept for the history while the website lists it. */
    abstract var finished: Boolean
}

class StoredSender(
    override val id: String,
    override val peerDeviceId: String,
    val files: List<SourceFile>,
    override var finished: Boolean = false,
) : StoredTransfer()

class StoredReceiver(
    override val id: String,
    override val peerDeviceId: String,
    var manifest: List<ManifestFile>? = null,
    /** Where the files go ([DropFolders]); set when the user accepted. */
    var folder: String? = null,
    var targets: List<TargetFile>? = null,
    /** "accepted", "declined" or null while the user has not answered. */
    var decision: String? = null,
    override var finished: Boolean = false,
) : StoredTransfer()

/** Values kept encrypted on the phone (SecureFileStore). */
interface DropSecrets {
    fun get(name: String): ByteArray?

    fun put(name: String, value: ByteArray)
}

class DropStore(private val file: File, private val scope: CoroutineScope) {
    private var folderValue: String? = null
    private var items = listOf<StoredTransfer>()
    private val writeLock = Mutex()
    private var writing: Job? = null

    suspend fun load() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val value = runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject ?: return@withContext
        if (number(value["version"]) != 1L) return@withContext
        folderValue = text(value["folder"])
        items = (value["transfers"] as? JsonArray).orEmpty().mapNotNull(::parseTransfer)
    }

    var folder: String?
        get() = folderValue
        set(value) {
            folderValue = value
            save()
        }

    val transfers: List<StoredTransfer> get() = items

    fun find(id: String): StoredTransfer? = items.firstOrNull { it.id == id }

    fun put(transfer: StoredTransfer) {
        items = items.filterNot { it.id == transfer.id } + transfer
        save()
    }

    fun remove(id: String) {
        items = items.filterNot { it.id == id }
        save()
    }

    /** Writes are queued: the last state always wins. */
    fun save() {
        val text = encode().toString()
        val previous = writing
        writing = scope.launch(Dispatchers.IO) {
            previous?.join()
            writeLock.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeText(text)
                    if (!temporary.renameTo(file)) {
                        file.delete()
                        temporary.renameTo(file)
                    }
                }
            }
        }
    }

    suspend fun flush() {
        writing?.join()
    }

    private fun encode(): JsonObject = JsonObject(
        mapOf(
            "version" to JsonPrimitive(1),
            "folder" to (folderValue?.let(::JsonPrimitive) ?: JsonNull),
            "transfers" to JsonArray(items.map(::encodeTransfer)),
        ),
    )

    private fun encodeTransfer(transfer: StoredTransfer): JsonObject = when (transfer) {
        is StoredSender -> JsonObject(
            mapOf(
                "id" to JsonPrimitive(transfer.id),
                "role" to JsonPrimitive("sender"),
                "peerDeviceId" to JsonPrimitive(transfer.peerDeviceId),
                "files" to JsonArray(
                    transfer.files.map {
                        JsonObject(
                            mapOf(
                                "ref" to JsonPrimitive(it.ref),
                                "name" to JsonPrimitive(it.name),
                                "size" to JsonPrimitive(it.size),
                                "modifiedMs" to JsonPrimitive(it.modifiedMs),
                            ),
                        )
                    },
                ),
                "finished" to JsonPrimitive(transfer.finished),
            ),
        )
        is StoredReceiver -> JsonObject(
            mapOf(
                "id" to JsonPrimitive(transfer.id),
                "role" to JsonPrimitive("receiver"),
                "peerDeviceId" to JsonPrimitive(transfer.peerDeviceId),
                "manifest" to (
                    transfer.manifest?.let { files ->
                        JsonArray(files.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "size" to JsonPrimitive(it.size))) })
                    } ?: JsonNull
                    ),
                "folder" to (transfer.folder?.let(::JsonPrimitive) ?: JsonNull),
                "targets" to (
                    transfer.targets?.let { targets ->
                        JsonArray(
                            targets.map {
                                JsonObject(
                                    mapOf(
                                        "name" to JsonPrimitive(it.name),
                                        "size" to JsonPrimitive(it.size),
                                        "partial" to JsonPrimitive(it.partial),
                                        "final" to (it.final?.let(::JsonPrimitive) ?: JsonNull),
                                    ),
                                )
                            },
                        )
                    } ?: JsonNull
                    ),
                "decision" to (transfer.decision?.let(::JsonPrimitive) ?: JsonNull),
                "finished" to JsonPrimitive(transfer.finished),
            ),
        )
    }

    private fun text(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.length < 4_096 }

    private fun number(value: JsonElement?): Long? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

    private fun parseSource(value: JsonElement): SourceFile? {
        val obj = value as? JsonObject ?: return null
        return SourceFile(
            ref = text(obj["ref"]) ?: return null,
            name = text(obj["name"]) ?: return null,
            size = number(obj["size"]) ?: return null,
            modifiedMs = number(obj["modifiedMs"]) ?: return null,
        )
    }

    private fun parseTarget(value: JsonElement): TargetFile? {
        val obj = value as? JsonObject ?: return null
        val final = obj["final"]
        return TargetFile(
            name = text(obj["name"]) ?: return null,
            size = number(obj["size"]) ?: return null,
            partial = text(obj["partial"]) ?: return null,
            final = if (final == null || final is JsonNull) null else text(final) ?: return null,
        )
    }

    private fun parseManifest(value: JsonElement?): List<ManifestFile>? {
        val array = value as? JsonArray ?: return null
        val files = array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ManifestFile(text(obj["name"]) ?: return@mapNotNull null, number(obj["size"]) ?: return@mapNotNull null)
        }
        return files.takeIf { it.size == array.size }
    }

    private fun parseTransfer(value: JsonElement): StoredTransfer? {
        val obj = value as? JsonObject ?: return null
        val id = text(obj["id"])?.takeIf(DropWire::isTransferId) ?: return null
        val peer = text(obj["peerDeviceId"]) ?: return null
        val finished = (obj["finished"] as? JsonPrimitive)?.booleanOrNull == true
        return when (text(obj["role"])) {
            "sender" -> {
                val array = obj["files"] as? JsonArray ?: return null
                val files = array.mapNotNull(::parseSource)
                if (files.size != array.size) return null
                StoredSender(id, peer, files, finished)
            }
            "receiver" -> {
                val targetArray = obj["targets"] as? JsonArray
                val targets = targetArray?.mapNotNull(::parseTarget)
                if (targetArray != null && targets?.size != targetArray.size) return null
                StoredReceiver(
                    id = id,
                    peerDeviceId = peer,
                    manifest = parseManifest(obj["manifest"]),
                    folder = text(obj["folder"]),
                    targets = targets,
                    decision = text(obj["decision"])?.takeIf { it == "accepted" || it == "declined" },
                    finished = finished,
                )
            }
            else -> null
        }
    }
}

/** The key each peer device showed when its user confirmed the code. */
class DropPins(private val secrets: DropSecrets) {
    private var pins = mapOf<String, String>()

    fun load() {
        pins = runCatching {
            val raw = secrets.get(PINS) ?: return@runCatching emptyMap()
            val value = Json.parseToJsonElement(raw.decodeToString()) as? JsonObject ?: return@runCatching emptyMap()
            value.mapNotNull { (device, hash) ->
                (hash as? JsonPrimitive)?.takeIf { it.isString && HASH.matches(it.content) }?.let { device to it.content }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun trust(deviceId: String, peerKey: ByteArray): Trust {
        val pinned = pins[deviceId] ?: return Trust.NEW
        return if (pinned == DropWire.keyHash(peerKey)) Trust.TRUSTED else Trust.CHANGED
    }

    fun pin(deviceId: String, peerKey: ByteArray) {
        pins = pins + (deviceId to DropWire.keyHash(peerKey))
        secrets.put(PINS, JsonObject(pins.mapValues { JsonPrimitive(it.value) }).toString().encodeToByteArray())
    }

    private companion object {
        const val PINS = "drop_pins"
        val HASH = Regex("^[0-9a-f]{64}$")
    }
}
