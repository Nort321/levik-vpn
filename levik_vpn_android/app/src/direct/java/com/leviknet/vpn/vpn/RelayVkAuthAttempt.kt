package com.leviknet.vpn.vpn

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal sealed interface RelayVkAuthResult {
    data class Success(val credentials: RelayTurnCredentials) : RelayVkAuthResult
    data class Failure(val error: Throwable) : RelayVkAuthResult
}

/** A late WebView result or destruction belongs only to the attempt that opened it. */
internal class RelayVkAuthAttempt(val hash: String) {
    val id: String = UUID.randomUUID().toString()
    private val result = AtomicReference<RelayVkAuthResult?>(null)
    private val finished = CountDownLatch(1)
    private val observer = AtomicReference<(() -> Unit)?>(null)

    val isCompleted: Boolean get() = result.get() != null

    fun complete(credentials: RelayTurnCredentials): Boolean = finish(RelayVkAuthResult.Success(credentials))

    fun cancel(error: Throwable): Boolean = finish(RelayVkAuthResult.Failure(error))

    fun await(timeoutMs: Long): RelayVkAuthResult? {
        finished.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return result.get()
    }

    fun observeCompletion(callback: () -> Unit) {
        observer.set(callback)
        if (isCompleted) observer.getAndSet(null)?.invoke()
    }

    fun removeObserver(callback: () -> Unit) {
        observer.compareAndSet(callback, null)
    }

    private fun finish(value: RelayVkAuthResult): Boolean {
        if (!result.compareAndSet(null, value)) return false
        finished.countDown()
        observer.getAndSet(null)?.invoke()
        return true
    }
}

internal class RelayVkAuthException(val stableCode: String) : IllegalStateException(stableCode)

internal object RelayVkAuthTiming {
    // Stay below the native account request's five-minute deadline, including silent login.
    const val TOTAL_TIMEOUT_MS = 285_000L
    const val HEADLESS_TIMEOUT_MS = 45_000L

    fun remaining(startedAtMs: Long, nowMs: Long, credentialDeadlineMs: Long = Long.MAX_VALUE): Long =
        minOf(
            (TOTAL_TIMEOUT_MS - (nowMs - startedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L),
            (credentialDeadlineMs - nowMs).coerceAtLeast(0L),
        )
}

/** User interaction pauses the transport budget, but never extends the signed credential. */
internal class RelayPreparationTimeout(
    nowMs: Long,
    private val credentialDeadlineMs: Long,
    transportTimeoutMs: Long,
) {
    private var transportDeadlineMs = addSaturated(nowMs, transportTimeoutMs)
    private var authPauseRemainingMs = RelayVkAuthTiming.TOTAL_TIMEOUT_MS

    val deadlineMs: Long get() = minOf(transportDeadlineMs, credentialDeadlineMs)

    fun accountForAuthentication(startedAtMs: Long, finishedAtMs: Long) {
        val pause = minOf((finishedAtMs - startedAtMs).coerceAtLeast(0L), authPauseRemainingMs)
        authPauseRemainingMs -= pause
        transportDeadlineMs = addSaturated(transportDeadlineMs, pause)
    }

    private fun addSaturated(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment
}
