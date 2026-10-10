package com.leviknet.vpn.core.telemetry

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class QueuedSession(
    /** Wall-clock session start, for ageS at send time. */
    val startedAt: Long,
    val savedAt: Long,
    val body: JsonObject,
) {
    val sid: String get() = body["sid"]?.jsonPrimitive?.content.orEmpty()
    val seq: Int get() = body["seq"]?.jsonPrimitive?.int ?: 0
    val final: Boolean get() = body["final"]?.jsonPrimitive?.content == "true"
}

@Serializable
private data class InstallId(val id: String, val day: String)

@Serializable
private data class QueueFile(
    val version: Int = 1,
    val install: InstallId,
    val sessions: List<QueuedSession>,
)

const val MAX_QUEUE_AGE_MS = 7L * 24 * 60 * 60 * 1_000
private const val MAX_QUEUED_SESSIONS = 300
private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private fun utcDay(now: Long): String = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()

/**
 * Anonymous reports waiting to be sent. The file holds no account or device
 * data, so it is plain JSON in no-backup storage; writes are atomic.
 */
class TelemetryQueue(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: QueueFile? = null

    /** A random identifier replaced every UTC day; see "Identifiers" in the contract. */
    @Synchronized
    fun installId(): String {
        val current = load()
        val day = utcDay(now())
        if (current.install.day == day) return current.install.id
        val install = InstallId(UUID.randomUUID().toString(), day)
        save(current.copy(install = install))
        return install.id
    }

    /** Keeps only the latest checkpoint of each session. */
    @Synchronized
    fun put(entry: QueuedSession) {
        val current = load()
        val index = current.sessions.indexOfFirst { it.sid == entry.sid }
        if (index >= 0 && current.sessions[index].seq >= entry.seq) return
        val sessions = current.sessions.toMutableList()
        if (index >= 0) sessions[index] = entry else sessions += entry
        save(current.copy(sessions = prune(sessions)))
    }

    @Synchronized
    fun pending(): List<QueuedSession> {
        val current = load()
        val pruned = prune(current.sessions)
        if (pruned.size != current.sessions.size) save(current.copy(sessions = pruned))
        return pruned
    }

    /** Removes sent checkpoints unless a newer one was queued meanwhile. */
    @Synchronized
    fun acknowledge(sent: Map<String, Int>) {
        val current = load()
        val remaining = current.sessions.filter { entry ->
            val seq = sent[entry.sid]
            seq == null || entry.seq > seq
        }
        if (remaining.size != current.sessions.size) save(current.copy(sessions = remaining))
    }

    @Synchronized
    fun clear() {
        state = emptyQueue()
        file().delete()
    }

    private fun prune(sessions: List<QueuedSession>): List<QueuedSession> {
        val oldest = now() - MAX_QUEUE_AGE_MS
        return sessions.filter { it.savedAt >= oldest }.takeLast(MAX_QUEUED_SESSIONS)
    }

    private fun load(): QueueFile {
        state?.let { return it }
        // Missing or damaged: start over, the data is disposable.
        val loaded = runCatching { json.decodeFromString<QueueFile>(file().readText()) }
            .getOrNull()
            ?.takeIf { it.version == 1 && UUID_PATTERN.matches(it.install.id) }
            ?: emptyQueue()
        state = loaded
        return loaded
    }

    private fun save(value: QueueFile) {
        state = value
        directory.mkdirs()
        val temporary = File(directory, "queue.json.tmp")
        temporary.writeText(json.encodeToString(QueueFile.serializer(), value))
        if (!temporary.renameTo(file())) {
            temporary.delete()
            error("Unable to replace the telemetry queue")
        }
    }

    private fun emptyQueue() = QueueFile(install = InstallId(UUID.randomUUID().toString(), utcDay(now())), sessions = emptyList())

    private fun file() = File(directory, "queue.json")
}

const val MAX_BATCH_SESSIONS = 20
const val MAX_BATCH_BYTES = 128 * 1024
private const val MAX_AGE_S = (31L + 7) * 24 * 60 * 60

data class TelemetryBatch(val body: String, val sessions: Map<String, Int>)

/** Request bodies that respect the server's count and size limits. */
fun buildTelemetryBatches(install: String, entries: List<QueuedSession>, now: Long): List<TelemetryBatch> {
    val batches = mutableListOf<TelemetryBatch>()
    val current = mutableListOf<JsonObject>()
    fun envelope(sessions: List<JsonObject>) = buildJsonObject {
        put("install", install)
        put("sessions", JsonArray(sessions))
    }.toString()
    fun flush() {
        if (current.isEmpty()) return
        batches += TelemetryBatch(
            body = envelope(current),
            sessions = current.associate { session ->
                session.getValue("sid").jsonPrimitive.content to session.getValue("seq").jsonPrimitive.int
            },
        )
        current.clear()
    }
    val envelopeBytes = envelope(emptyList()).toByteArray().size
    var bytes = envelopeBytes
    for (entry in entries) {
        val ageS = ((now - entry.startedAt) / 1_000).coerceIn(0, MAX_AGE_S)
        val session = JsonObject(entry.body + ("ageS" to JsonPrimitive(ageS)))
        val size = session.toString().toByteArray().size + 1
        if (size + envelopeBytes > MAX_BATCH_BYTES) continue
        if (current.size >= MAX_BATCH_SESSIONS || bytes + size > MAX_BATCH_BYTES) {
            flush()
            bytes = envelopeBytes
        }
        current += session
        bytes += size
    }
    flush()
    return batches
}
