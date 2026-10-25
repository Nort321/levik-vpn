package com.leviknet.vpn.core.drop

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The connection is over (closed, silent, or a frame did not come in time). */
class DropChannelClosed(message: String = "connection closed") : IOException(message)

/**
 * `u32 big-endian length | payload` over a socket, docs/levik-drop.md
 * "Relay". A reader thread fills a short queue the session pulls from; while
 * the queue is full the reader stops, so a slow disk slows the peer down
 * instead of filling memory.
 */
class FrameChannel(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val maxFrame: Int = DropWire.MAX_FRAME,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val queue = Channel<ByteArray>(QUEUE_FRAMES)
    private val writeLock = Mutex()
    private val output = socket.getOutputStream()
    private val shut = AtomicBoolean(false)
    private val closeListeners = mutableListOf<() -> Unit>()

    @Volatile
    private var lastHeard = clock()

    @Volatile
    private var idleMs = 0L
    private var keepalive: Job? = null
    private var watchdog: Job? = null
    private val reader: Job

    init {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        reader = scope.launch(Dispatchers.IO) { readFrames() }
    }

    val closed: Boolean get() = shut.get()

    /** Runs once the connection is over; at once when it already is. */
    fun onClose(listener: () -> Unit) {
        val now = synchronized(closeListeners) {
            if (shut.get()) true else {
                closeListeners += listener
                false
            }
        }
        if (now) listener()
    }

    /** The next frame; a keepalive frame never comes out. */
    suspend fun next(timeoutMs: Long = 0): ByteArray {
        if (timeoutMs <= 0) return receive()
        return withTimeoutOrNull(timeoutMs) { receive() } ?: throw DropChannelClosed("timed out")
    }

    private suspend fun receive(): ByteArray = try {
        queue.receive()
    } catch (_: Exception) {
        throw DropChannelClosed()
    }

    /** Returns once the socket took the frame. */
    suspend fun send(payload: ByteArray) {
        if (payload.isEmpty() || payload.size > maxFrame) throw IOException("bad frame size")
        writeLock.withLock {
            if (shut.get()) throw DropChannelClosed()
            withContext(Dispatchers.IO) {
                try {
                    output.write(ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array())
                    output.flush()
                } catch (error: IOException) {
                    shutdown()
                    throw DropChannelClosed(error.message ?: "write failed")
                }
            }
        }
    }

    /** A keepalive frame every [ms] (the relay closes connections silent for 60 s). */
    fun startKeepalive(ms: Long = KEEPALIVE_MS) {
        if (keepalive != null) return
        keepalive = scope.launch {
            while (isActive && !shut.get()) {
                delay(ms)
                runCatching { send(DropWire.KEEPALIVE) }
            }
        }
    }

    /** Closes the connection when nothing, not even a keepalive, came for [ms]; 0 stops it. */
    fun watchIdle(ms: Long) {
        idleMs = ms
        lastHeard = clock()
        if (watchdog != null) return
        watchdog = scope.launch {
            while (isActive && !shut.get()) {
                delay(WATCH_STEP_MS)
                val limit = idleMs
                if (limit > 0 && clock() - lastHeard > limit) {
                    close()
                    return@launch
                }
            }
        }
    }

    fun close() {
        shutdown()
    }

    /** Lets frames already handed to [send] go out (pause, cancel), then closes. */
    fun closeGracefully() {
        if (shut.get()) return
        scope.launch {
            withTimeoutOrNull(GRACE_MS) { writeLock.withLock { } }
            close()
        }
    }

    private suspend fun readFrames() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), READ_BUFFER))
            while (true) {
                val length = input.readInt()
                if (length < 1 || length > maxFrame) throw IOException("bad frame")
                val frame = ByteArray(length)
                input.readFully(frame)
                lastHeard = clock()
                if (length == 1 && frame[0] == DropWire.KEEPALIVE[0]) continue
                queue.send(frame)
            }
        } catch (_: EOFException) {
            // The other side closed.
        } catch (_: IOException) {
            // Closed here, reset, or a bad frame.
        } catch (_: Exception) {
            // The scope ended.
        } finally {
            shutdown()
        }
    }

    private fun shutdown() {
        if (!shut.compareAndSet(false, true)) return
        keepalive?.cancel()
        watchdog?.cancel()
        // Frames already queued are still read first.
        queue.close()
        runCatching { socket.close() }
        val listeners = synchronized(closeListeners) { closeListeners.toList().also { closeListeners.clear() } }
        listeners.forEach { runCatching(it) }
    }

    private companion object {
        const val QUEUE_FRAMES = 16
        const val READ_BUFFER = 64 * 1024
        const val KEEPALIVE_MS = 20_000L
        const val WATCH_STEP_MS = 1_000L
        const val GRACE_MS = 2_000L
    }
}
