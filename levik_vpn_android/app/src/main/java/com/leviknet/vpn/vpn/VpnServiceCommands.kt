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
    private var pendingStop: Job? = null

    val isStopping: Boolean
        get() = pendingStop?.isCompleted == false

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
}
