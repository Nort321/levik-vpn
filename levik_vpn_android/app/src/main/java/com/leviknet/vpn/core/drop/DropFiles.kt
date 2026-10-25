package com.leviknet.vpn.core.drop

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Files on both ends of a transfer, docs/levik-drop.md "Records" and "Saving". */

/** The transfer cannot go on; [reason] is one of [DropWire.FAILURES]. */
class TransferFailure(val reason: String, message: String = reason) : Exception(message)

/** Something the user can fix before sending: the UI shows it. */
class DropUserError(val kind: Kind, val detail: String? = null) : Exception(kind.name) {
    enum class Kind { SIGNED_OUT, BUSY, NOTHING, TOO_MANY_FILES, TOO_LARGE, UNREADABLE, NOT_A_FILE, BAD_RESPONSE, STALE, NO_FOLDER }
}

// Sender -------------------------------------------------------------------------------------

/** A file the user picked; [ref] is a content:// URI on the phone. */
data class SourceFile(val ref: String, val name: String, val size: Long, val modifiedMs: Long)

data class SourceStat(val size: Long, val modifiedMs: Long)

interface DropSources {
    /** Name, size and modification time of what the user picked; throws [DropUserError]. */
    suspend fun describe(refs: List<String>): List<SourceFile>

    /** What the file is like now; null when it cannot be read. */
    fun stat(file: SourceFile): SourceStat?

    /** Reads from the start: content:// sources need not seek. */
    fun open(file: SourceFile): InputStream
}

private fun unchanged(file: SourceFile, now: SourceStat): Boolean =
    now.size == file.size && (file.modifiedMs == 0L || now.modifiedMs == 0L || now.modifiedMs == file.modifiedMs)

/**
 * Reads [file] from its start: the first [offset] bytes only go into [digest]
 * and must match [prefix]; the rest goes to [emit] in chunks (and into [digest]).
 */
suspend fun readSource(
    sources: DropSources,
    file: SourceFile,
    offset: Long,
    prefix: String?,
    digest: MessageDigest,
    emit: suspend (ByteArray, Int) -> Unit,
) {
    if (offset > file.size || (offset > 0 && offset < file.size && prefix == null)) throw TransferFailure("protocol")
    val input = withContext(Dispatchers.IO) {
        val now = sources.stat(file) ?: throw TransferFailure("source_unreadable")
        if (!unchanged(file, now)) throw TransferFailure("source_changed")
        try {
            sources.open(file)
        } catch (_: Exception) {
            throw TransferFailure("source_unreadable")
        }
    }
    try {
        val buffer = ByteArray(DropWire.MAX_CHUNK)
        var position = 0L
        suspend fun read(length: Int) = withContext(Dispatchers.IO) {
            var filled = 0
            while (filled < length) {
                val count = try {
                    input.read(buffer, filled, length - filled)
                } catch (_: IOException) {
                    throw TransferFailure("source_unreadable")
                }
                if (count < 0) throw TransferFailure("source_changed")
                filled += count
            }
            position += length
            digest.update(buffer, 0, length)
        }
        while (position < offset) read(minOf(DropWire.MAX_CHUNK.toLong(), offset - position).toInt())
        if (offset > 0 && offset < file.size) {
            val kept = (digest.clone() as MessageDigest).digest().toHex()
            if (kept != prefix) throw TransferFailure("source_changed")
        }
        while (position < file.size) {
            val length = minOf(DropWire.MAX_CHUNK.toLong(), file.size - position).toInt()
            read(length)
            emit(buffer, length)
        }
        // The file grew or changed while it was read.
        withContext(Dispatchers.IO) {
            val grown = runCatching { input.read() }.getOrDefault(-1) >= 0
            val now = sources.stat(file)
            if (grown || now == null || !unchanged(file, now)) throw TransferFailure("source_changed")
        }
    } finally {
        withContext(Dispatchers.IO) { runCatching { input.close() } }
    }
}

// Receiver -----------------------------------------------------------------------------------

class TargetFile(
    /** Safe name the file gets (before " (1)" numbering). */
    val name: String,
    val size: Long,
    /** The hidden entry the bytes go to while they arrive. */
    var partial: String,
    /** Where the finished file lies; null until it is in place. */
    var final: String?,
)

/** An open partial entry: writes go on from where it was opened. */
interface TargetWriter : Closeable {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Flushes to the disk before the file is put in place. */
    fun sync()
}

/** Where received files go: Downloads/Levik through MediaStore, or a folder the user picked. */
interface DropTargets {
    /** Free space there; null when it cannot be told. */
    fun freeBytes(): Long?

    /** A fresh hidden entry for the arriving file [name]. */
    fun createPartial(name: String): String

    /** Bytes in [partial]; null when it is gone. */
    fun length(partial: String): Long?

    /** Opens [partial] cut to [offset] bytes, writing from there. */
    fun openWrite(partial: String, offset: Long): TargetWriter

    fun openRead(partial: String): InputStream

    /** Moves the finished file to its name; never overwrites. Returns where it lies. */
    fun place(partial: String, name: String): String

    fun delete(partial: String)
}

/** An I/O error while saving: a full disk or anything else. */
fun storageFailure(error: Throwable): TransferFailure {
    val text = error.message.orEmpty()
    val full = text.contains("ENOSPC") || text.contains("EDQUOT") || text.contains("No space left", ignoreCase = true)
    return TransferFailure(if (full) "storage_full" else "storage_error", text.ifEmpty { "storage" })
}

/** The receiving side's files: partial entries, offsets for `accept`, finishing. */
class TargetFiles(
    private val store: DropTargets,
    val targets: List<TargetFile>,
    /** A file is in place: written down at once, so a restart does not offer it again. */
    private val placed: suspend (TargetFile) -> Unit,
) {
    private class Writing(val index: Int, val writer: TargetWriter, var written: Long, val digest: MessageDigest)

    private class Known(val length: Long, val digest: MessageDigest)

    private val lock = Mutex()
    private var writing: Writing? = null

    /** Hashes of partial entries as of [offsets], so writing continues them. */
    private val hashes = mutableMapOf<Int, Known>()

    fun doneBytes(offsets: List<Long>): Long = offsets.sum()

    /** The accept fields: what is kept of each file and the SHA-256 of that prefix. */
    data class Kept(val offsets: List<Long>, val prefix: List<String?>)

    /**
     * What is kept of each file. A partial entry that already has every byte is
     * cut by one, so the sender's `end` still checks it.
     */
    suspend fun offsets(): Kept = lock.withLock {
        closeWritingLocked()
        withContext(Dispatchers.IO) {
            val offsets = mutableListOf<Long>()
            val prefix = mutableListOf<String?>()
            targets.forEachIndexed { index, target ->
                if (target.final != null) {
                    offsets += target.size
                    prefix += null
                    return@forEachIndexed
                }
                val length = runCatching { store.length(target.partial) }.getOrNull()
                val keep = minOf(length ?: 0L, maxOf(0L, target.size - 1))
                val digest = MessageDigest.getInstance("SHA-256")
                val kept = if (length == null) {
                    renew(target)
                    0L
                } else if (keep == 0L) {
                    0L
                } else if (hashPrefix(target, length, keep, digest)) {
                    keep
                } else {
                    renew(target)
                    digest.reset()
                    0L
                }
                offsets += kept
                prefix += if (kept > 0) (digest.clone() as MessageDigest).digest().toHex() else null
                hashes[index] = Known(kept, digest)
            }
            Kept(offsets, prefix)
        }
    }

    private fun hashPrefix(target: TargetFile, length: Long, keep: Long, digest: MessageDigest): Boolean = try {
        if (length != keep) store.openWrite(target.partial, keep).close()
        store.openRead(target.partial).use { input ->
            val buffer = ByteArray(DropWire.MAX_CHUNK)
            var position = 0L
            while (position < keep) {
                val count = input.read(buffer, 0, minOf(DropWire.MAX_CHUNK.toLong(), keep - position).toInt())
                if (count < 0) break
                digest.update(buffer, 0, count)
                position += count
            }
            position == keep
        }
    } catch (_: Exception) {
        false
    }

    /** A partial entry that is gone or unreadable starts over in a new one. */
    private fun renew(target: TargetFile) {
        runCatching { store.delete(target.partial) }
        target.partial = try {
            store.createPartial(target.name)
        } catch (error: Exception) {
            throw storageFailure(error)
        }
    }

    /** Writes the next bytes of [index]; anything but the next offset is a protocol error. Returns the bytes written. */
    suspend fun write(index: Int, offset: Long, bytes: ByteArray): Long = lock.withLock {
        val target = targets.getOrNull(index)
        if (target == null || target.final != null) throw TransferFailure("protocol")
        val current = writing?.takeIf { it.index == index } ?: openWriting(index, target).also {
            if (it.written != offset) throw TransferFailure("protocol")
        }
        if (offset != current.written || offset + bytes.size > target.size) throw TransferFailure("protocol")
        withContext(Dispatchers.IO) {
            try {
                current.writer.write(bytes, 0, bytes.size)
            } catch (error: Exception) {
                throw storageFailure(error)
            }
        }
        current.digest.update(bytes)
        current.written += bytes.size
        current.written
    }

    private suspend fun openWriting(index: Int, target: TargetFile): Writing {
        closeWritingLocked()
        val known = hashes.remove(index) ?: Known(0L, MessageDigest.getInstance("SHA-256"))
        val writer = withContext(Dispatchers.IO) {
            try {
                store.openWrite(target.partial, known.length)
            } catch (error: Exception) {
                throw storageFailure(error)
            }
        }
        return Writing(index, writer, known.length, known.digest).also { writing = it }
    }

    /** `end`: the hash of the whole file must match; then it moves to its name. */
    suspend fun finish(index: Int, sha256: String): String = lock.withLock {
        val target = targets.getOrNull(index)
        if (target == null || target.final != null) throw TransferFailure("protocol")
        // Empty file, or nothing new since the last session.
        val current = writing?.takeIf { it.index == index } ?: openWriting(index, target)
        writing = null
        val final = withContext(Dispatchers.IO) {
            try {
                if (current.written != target.size) throw TransferFailure("protocol")
                if (current.digest.digest().toHex() != sha256) throw TransferFailure("integrity")
                try {
                    current.writer.sync()
                } catch (error: Exception) {
                    throw storageFailure(error)
                }
            } catch (error: TransferFailure) {
                runCatching { current.writer.close() }
                if (error.reason == "integrity") runCatching { store.delete(target.partial) }
                throw error
            }
            runCatching { current.writer.close() }
            try {
                store.place(target.partial, target.name)
            } catch (error: TransferFailure) {
                throw error
            } catch (error: Exception) {
                throw storageFailure(error)
            }
        }
        target.final = final
        placed(target)
        final
    }

    suspend fun closeWriting() = lock.withLock { closeWritingLocked() }

    private suspend fun closeWritingLocked() {
        val current = writing ?: return
        writing = null
        hashes[current.index] = Known(current.written, current.digest)
        withContext(Dispatchers.IO) { runCatching { current.writer.close() } }
    }

    /** Cancel, failure or expiry: partial entries go, finished files stay. */
    suspend fun discardPartials() {
        closeWriting()
        removePartials(store, targets)
    }

    companion object {
        /** Safe names and fresh partial entries for a manifest. */
        suspend fun plan(store: DropTargets, manifest: List<ManifestFile>): List<TargetFile> = withContext(Dispatchers.IO) {
            val planned = mutableListOf<TargetFile>()
            try {
                for (file in manifest) {
                    val name = DropNames.safeFileName(file.name)
                    planned += TargetFile(name, file.size, store.createPartial(name), null)
                }
            } catch (error: Exception) {
                planned.forEach { runCatching { store.delete(it.partial) } }
                throw if (error is TransferFailure) error else storageFailure(error)
            }
            planned
        }

        suspend fun removePartials(store: DropTargets, targets: List<TargetFile>) = withContext(Dispatchers.IO) {
            for (target in targets) if (target.final == null) runCatching { store.delete(target.partial) }
        }
    }
}
