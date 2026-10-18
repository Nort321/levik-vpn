package com.leviknet.vpn.vpn

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayVkAuthAttemptTest {
    private val credentials = RelayTurnCredentials("example-user", "example-password", listOf("turn:example.com:3478"))

    @Test
    fun `late completion and destruction of an old window cannot complete a new attempt`() {
        val old = RelayVkAuthAttempt("old-call")
        val current = RelayVkAuthAttempt("new-call")
        assertNotEquals(old.id, current.id)
        assertTrue(old.cancel(RelayVkAuthException("relay_vk_auth_cancelled")))
        assertFalse(old.complete(credentials))
        assertNull(current.await(0L))
        assertTrue(current.complete(credentials))
        assertFalse(old.cancel(IllegalStateException("late destruction")))
        assertEquals(credentials, (current.await(0L) as RelayVkAuthResult.Success).credentials)
    }

    @Test
    fun `timeout cleanup closes the matching window exactly once`() {
        val attempt = RelayVkAuthAttempt("call")
        val closed = AtomicInteger()
        attempt.observeCompletion { closed.incrementAndGet() }
        attempt.cancel(RelayVkAuthException("relay_vk_auth_timeout"))
        attempt.complete(credentials)
        assertEquals(1, closed.get())
        assertTrue(attempt.await(0L) is RelayVkAuthResult.Failure)
    }

    @Test
    fun `a window created after cancellation closes and a removed observer is not called`() {
        val cancelled = RelayVkAuthAttempt("cancelled")
        cancelled.cancel(RelayVkAuthException("relay_vk_auth_cancelled"))
        val closed = AtomicInteger()
        cancelled.observeCompletion { closed.incrementAndGet() }
        assertEquals(1, closed.get())

        val active = RelayVkAuthAttempt("active")
        val observer: () -> Unit = { closed.incrementAndGet(); Unit }
        active.observeCompletion(observer)
        active.removeObserver(observer)
        active.complete(credentials)
        assertEquals(1, closed.get())
    }

    @Test
    fun `silent login is included in the native auth time budget and credential expiry wins`() {
        assertEquals(240_000L, RelayVkAuthTiming.remaining(1_000L, 46_000L))
        assertEquals(0L, RelayVkAuthTiming.remaining(1_000L, 286_000L))
        assertEquals(10_000L, RelayVkAuthTiming.remaining(1_000L, 46_000L, 56_000L))
        assertEquals(0L, RelayVkAuthTiming.remaining(1_000L, 46_000L, 40_000L))
    }

    @Test
    fun `interactive login can exceed transport timeout without consuming its remaining budget`() {
        val timeout = RelayPreparationTimeout(0L, 600_000L, 120_000L)
        timeout.accountForAuthentication(10_000L, 200_000L)
        assertEquals(310_000L, timeout.deadlineMs)
        assertTrue(timeout.deadlineMs > 200_000L)
    }

    @Test
    fun `authentication never extends credential expiry or creates an unlimited prepare wait`() {
        val credentialLimited = RelayPreparationTimeout(0L, 180_000L, 120_000L)
        credentialLimited.accountForAuthentication(10_000L, 200_000L)
        assertEquals(180_000L, credentialLimited.deadlineMs)

        val repeated = RelayPreparationTimeout(0L, 900_000L, 120_000L)
        repeated.accountForAuthentication(0L, 200_000L)
        repeated.accountForAuthentication(200_000L, 500_000L)
        assertEquals(405_000L, repeated.deadlineMs)
    }

    @Test
    fun `disconnect interrupts the pending login wait before its time budget expires`() = runBlocking {
        val attempt = RelayVkAuthAttempt("pending-login")
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            runInterruptible {
                started.countDown()
                try {
                    attempt.await(RelayVkAuthTiming.TOTAL_TIMEOUT_MS)
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(2_000L) { job.join() }
            assertTrue(interrupted.get())
        } finally {
            // Release the waiter even if cancellation regresses, so a failing test stays bounded.
            attempt.cancel(RelayVkAuthException("relay_vk_auth_cancelled"))
            job.cancelAndJoin()
        }
    }
}
