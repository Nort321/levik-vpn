package com.leviknet.vpn.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnServiceCommandsTest {
    @Test
    fun `off then on waits for teardown even before the stop coroutine starts`() = runTest {
        val commands = VpnServiceCommands(this)
        val releaseStop = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        commands.onStart(1)
        commands.enqueueStop {
            events += "stop started"
            releaseStop.await()
            events += "old tun closed"
        }

        // onStartCommand must not take the old coreRunning fast path in this window.
        assertTrue(commands.isStopping)
        commands.onStart(2)
        launch {
            commands.awaitPendingStop()
            events += "new core started"
        }
        runCurrent()
        assertEquals(listOf("stop started"), events)

        releaseStop.complete(Unit)
        runCurrent()
        assertEquals(listOf("stop started", "old tun closed", "new core started"), events)
        assertFalse(commands.isStopping)
    }

    @Test
    fun `a late off cannot finalize a newer foreground service start`() = runTest {
        val commands = VpnServiceCommands(this)
        val releaseStop = CompletableDeferred<Unit>()
        var removedForeground = false
        commands.onStart(10)
        commands.enqueueStop {
            releaseStop.await()
            if (commands.isLatest(10)) removedForeground = true
        }
        runCurrent()
        commands.onStart(11)
        releaseStop.complete(Unit)
        runCurrent()

        assertFalse(removedForeground)
        assertFalse(commands.isLatest(10))
        assertTrue(commands.isLatest(11))
    }

    @Test
    fun `off on off on finishes every stop before starting the final connection`() = runTest {
        val commands = VpnServiceCommands(this)
        val releaseFirstStop = CompletableDeferred<Unit>()
        val releaseSecondStop = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        commands.onStart(1)
        commands.enqueueStop {
            releaseFirstStop.await()
            events += "first stop"
        }
        commands.onStart(2)
        val cancelledConnection = launch {
            commands.awaitPendingStop()
            events += "cancelled connection started"
        }
        runCurrent()
        commands.onStart(3)
        cancelledConnection.cancel()
        commands.enqueueStop {
            events += "second stop started"
            releaseSecondStop.await()
            events += "second stop finished"
        }
        commands.onStart(4)
        launch {
            commands.awaitPendingStop()
            events += "final connection"
        }
        runCurrent()
        assertTrue(events.isEmpty())

        releaseFirstStop.complete(Unit)
        runCurrent()
        assertEquals(listOf("first stop", "second stop started"), events)
        assertTrue(commands.isStopping)

        releaseSecondStop.complete(Unit)
        runCurrent()
        assertEquals(
            listOf("first stop", "second stop started", "second stop finished", "final connection"),
            events,
        )
        assertFalse(commands.isStopping)
    }

    @Test
    fun `cancelling an on request does not cancel the service teardown`() = runTest {
        val commands = VpnServiceCommands(this)
        val releaseStop = CompletableDeferred<Unit>()
        var stopped = false
        commands.onStart(1)
        commands.enqueueStop {
            releaseStop.await()
            stopped = true
        }
        val connection = launch { commands.awaitPendingStop() }
        runCurrent()
        connection.cancel()
        runCurrent()
        assertTrue(commands.isStopping)

        releaseStop.complete(Unit)
        runCurrent()
        assertTrue(stopped)
        assertFalse(commands.isStopping)
    }

    @Test
    fun `an unsuperseded stop may finalize and later connections are not blocked`() = runTest {
        val commands = VpnServiceCommands(this)
        commands.onStart(1)
        var finalized = false
        commands.enqueueStop { finalized = commands.isLatest(1) }
        runCurrent()
        assertTrue(finalized)
        assertFalse(commands.isStopping)

        commands.onStart(2)
        commands.awaitPendingStop()
        assertTrue(commands.isLatest(2))
    }
}
