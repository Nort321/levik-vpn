package com.leviknet.vpn.vpn

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps teardown ordered without letting cancellation of a new connection cancel teardown. */
internal class VpnServiceCommands(private val scope: CoroutineScope) {
    private val latestStartId = AtomicInteger()
    @Volatile
    private var connectionIntent = ConnectionIntent(0L, true)
    @Volatile
    private var pendingStop: Job? = null
    @Volatile
    private var reconnectAttempt: ReconnectAttempt? = null

    val isStopping: Boolean
        get() = pendingStop?.isCompleted == false

    val connectionGeneration: Long?
        get() = connectionIntent.takeIf { it.requested }?.generation

    /** Invalidates callbacks queued before OFF, even if a newer ON has already arrived. */
    @Synchronized
    fun requestConnection(requested: Boolean) {
        if (connectionIntent.requested == requested) return
        connectionIntent = ConnectionIntent(connectionIntent.generation + 1L, requested)
    }

    fun permitsConnection(generation: Long): Boolean = connectionIntent.let {
        it.requested && it.generation == generation
    }

    fun permitsAutomaticConnection(generation: Long): Boolean =
        !isStopping && permitsConnection(generation)

    fun trackReconnect(generation: Long, job: Job) {
        reconnectAttempt = ReconnectAttempt(generation, job)
    }

    fun interruptReconnect(generation: Long) {
        reconnectAttempt?.takeIf { it.generation == generation }?.job?.cancel()
    }

    fun onStart(startId: Int) {
        latestStartId.set(startId)
    }

    fun isLatest(startId: Int): Boolean = latestStartId.get() == startId

    // Called from onStartCommand on the main thread. Publish the barrier before running teardown,
    // including when a test or a caller uses an immediate coroutine dispatcher.
    fun enqueueStop(stop: suspend () -> Unit) {
        val previousStop = pendingStop
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previousStop?.join()
            stop()
        }
        pendingStop = job
        job.start()
    }

    suspend fun awaitPendingStop() {
        pendingStop?.join()
    }

    private data class ConnectionIntent(val generation: Long, val requested: Boolean)
    private data class ReconnectAttempt(val generation: Long, val job: Job)
}
