package com.leviknet.vpn.core.telemetry

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

enum class SendOutcome { SENT, REJECTED, RETRY }

data class NetworkToken(val token: String, val expiresAt: Long)

interface TelemetryTransport {
    suspend fun send(body: String): SendOutcome
}

/** How a session left in the queue by a previous process ended, and when. */
data class InterruptedSessionEnd(val end: SessionEnd, val at: Long?)

private const val PERSIST_DELAY_MS = 5_000L
private const val CHECKPOINT_INTERVAL_MS = 30 * 60_000L
private const val FLUSH_INTERVAL_MS = 15 * 60_000L
private const val NETWORK_TOKEN_REUSE_MS = 10 * 60_000L

/**
 * Anonymous connection quality reports (docs/connection-telemetry.md in the
 * public repository). Nothing is recorded, stored or sent while disabled.
 *
 * Recording calls are cheap and safe from any thread; disk and network work
 * runs on [scope], which must use a background dispatcher.
 */
class ConnectionTelemetry(
    directory: File,
    private val client: TelemetryClientInfo,
    private val transport: TelemetryTransport,
    private val scope: CoroutineScope,
    private val interruptedEnd: (QueuedSession) -> InterruptedSessionEnd = {
        InterruptedSessionEnd(SessionEnd(EndBy.UNKNOWN, null), null)
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val queue = TelemetryQueue(directory, now)
    private val lock = Any()
    private val ioLock = Any()
    private val flushMutex = Mutex()
    private var enabled = false
    // Bumped when telemetry is switched off: work started earlier must not write again.
    @Volatile
    private var generation = 0
    private var session: SessionRecorder? = null
    private var persistJob: Job? = null
    private var checkpointJob: Job? = null
    private var flushLoopJob: Job? = null
    private var cachedToken: CachedToken? = null

    private data class CachedToken(val networkKey: String, val value: NetworkToken, val fetchedAt: Long)

    val active: Boolean
        get() = synchronized(lock) { enabled && session?.finished == false }

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (value == enabled) return
            enabled = value
            if (!value) {
                generation++
                session = null
                cachedToken = null
                persistJob?.cancel()
                checkpointJob?.cancel()
                flushLoopJob?.cancel()
                persistJob = null
                checkpointJob = null
                flushLoopJob = null
            } else {
                flushLoopJob = scope.launch {
                    while (isActive) {
                        delay(FLUSH_INTERVAL_MS)
                        flush()
                    }
                }
            }
        }
        val started = generation
        scope.launch {
            if (!value) {
                synchronized(ioLock) { runCatching { queue.clear() } }
                return@launch
            }
            finishInterrupted(started)
            flush()
        }
    }

    /**
     * Labels the session with the operator of the network it starts on. [fetch]
     * must go over that physical network, not through the tunnel; it runs in the
     * background so connecting is never delayed.
     */
    fun prepareNetwork(networkKey: String, type: NetworkType, fetch: suspend () -> NetworkToken?) {
        val reuse = synchronized(lock) {
            if (!enabled) return
            session?.takeIf { !it.finished }?.setNetwork(type)
            cachedToken?.takeIf {
                it.networkKey == networkKey && now() - it.fetchedAt < NETWORK_TOKEN_REUSE_MS && it.value.expiresAt > now()
            }
        }
        if (reuse != null) {
            attachToken(reuse.value.token)
            return
        }
        val started = generation
        scope.launch {
            val token = try {
                fetch()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@launch
            synchronized(lock) {
                if (!enabled || started != generation) return@launch
                cachedToken = CachedToken(networkKey, token, now())
            }
            attachToken(token.token)
        }
    }

    /** Starts a session unless one is already running. */
    fun begin(trigger: SessionTrigger, settings: TelemetrySessionSettings) {
        synchronized(lock) {
            if (!enabled || session?.finished == false) return
            session = SessionRecorder(client, trigger, settings, now)
            checkpointJob?.cancel()
            checkpointJob = scope.launch {
                while (isActive) {
                    delay(CHECKPOINT_INTERVAL_MS)
                    persist()
                    flush()
                }
            }
        }
    }

    fun record(update: (SessionRecorder) -> Unit) {
        synchronized(lock) {
            val current = session?.takeIf { enabled && !it.finished } ?: return
            update(current)
            if (persistJob?.isActive != true) {
                persistJob = scope.launch {
                    delay(PERSIST_DELAY_MS)
                    persist()
                }
            }
        }
    }

    /** Ends the session, saves it and sends it in the background. */
    fun finish(by: EndBy, code: String?) {
        val entry = synchronized(lock) {
            val current = session?.takeIf { enabled && !it.finished } ?: return
            current.end(by, code)
            session = null
            checkpointJob?.cancel()
            checkpointJob = null
            persistJob?.cancel()
            persistJob = null
            QueuedSession(current.startedAt, now(), current.snapshot())
        }
        val started = generation
        scope.launch {
            save(entry, started)
            flush()
        }
    }

    /** Sends shortly, e.g. once a tunnel is up and can carry the request. */
    fun flushSoon(delayMs: Long = 10_000L) {
        if (!synchronized(lock) { enabled }) return
        scope.launch {
            delay(delayMs)
            flush()
        }
    }

    /** Writes the current checkpoint now. */
    fun persist() {
        val entry = synchronized(lock) {
            persistJob?.cancel()
            persistJob = null
            val current = session?.takeIf { enabled } ?: return
            QueuedSession(current.startedAt, now(), current.snapshot())
        }
        save(entry, generation)
    }

    suspend fun flush() {
        if (!flushMutex.tryLock()) return
        try {
            val started = generation
            if (!synchronized(lock) { enabled }) return
            val (install, pending) = synchronized(ioLock) {
                if (started != generation) return
                runCatching { queue.installId() to queue.pending() }.getOrNull() ?: return
            }
            for (batch in buildTelemetryBatches(install, pending, now())) {
                val outcome = transport.send(batch.body)
                if (outcome == SendOutcome.RETRY) return
                synchronized(ioLock) {
                    if (started != generation) return
                    runCatching { queue.acknowledge(batch.sessions) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Kept in the queue for the next attempt.
        } finally {
            flushMutex.unlock()
        }
    }

    private fun attachToken(token: String) {
        synchronized(lock) {
            session?.takeIf { !it.finished && !it.hasNetworkToken }?.setNetworkToken(token)
        }
    }

    private fun save(entry: QueuedSession, started: Int) {
        synchronized(ioLock) {
            if (started != generation) return
            runCatching { queue.put(entry) }
        }
    }

    /** A checkpoint left by a process that stopped without ending its session. */
    private fun finishInterrupted(started: Int) {
        val currentSid = synchronized(lock) { session?.sid }
        synchronized(ioLock) {
            if (started != generation) return
            val pending = runCatching { queue.pending() }.getOrNull() ?: return
            for (entry in pending) {
                if (entry.final || entry.sid == currentSid) continue
                runCatching { queue.put(finalized(entry, interruptedEnd(entry))) }
            }
        }
    }
}

internal fun finalized(entry: QueuedSession, interrupted: InterruptedSessionEnd): QueuedSession {
    val lastT = entry.body["timeline"]?.jsonArray?.lastOrNull()?.jsonObject?.get("t")?.jsonPrimitive?.long ?: 0L
    val endedAt = interrupted.at?.takeIf { it >= entry.savedAt } ?: entry.savedAt
    val durationS = maxOf(lastT / 1_000, (endedAt - entry.startedAt) / 1_000)
    val body = JsonObject(
        entry.body +
            ("seq" to JsonPrimitive(entry.seq + 1)) +
            ("final" to JsonPrimitive(true)) +
            ("end" to endObject(interrupted.end, durationS)),
    )
    return entry.copy(body = body)
}
