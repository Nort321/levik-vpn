package com.leviknet.vpn.vpn

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One session owns MAIN writes, including acknowledged compensation after lost replies. */
internal class HotSwitchMainOwner(
    initialId: String,
    candidateIds: Set<String>,
    private val preflight: suspend (String) -> Boolean,
    private val install: suspend (String, () -> Boolean) -> Unit,
    private val confirmed: (String) -> Unit = {},
) : AutoCloseable {
    enum class Outcome { APPLIED, FAILED, UNCERTAIN, CANCELLED, UNSUPPORTED }
    data class Result(val outcome: Outcome, val serverId: String)
    private val candidates = candidateIds.toSet()
    private val writes = Mutex()
    private val epoch = AtomicLong()
    private val closed = AtomicBoolean()
    @Volatile var currentId: String = initialId
        private set
    @Volatile var certain: Boolean = true
        private set

    init { require(initialId in candidates && candidates.size <= SmartRouteSelection.MAX_CANDIDATES) }

    suspend fun select(targetId: String, canCommit: suspend () -> Boolean, proven: Boolean = false): Result {
        if (targetId !in candidates) return Result(Outcome.UNSUPPORTED, currentId)
        val intent = epoch.get()
        val request = currentCoroutineContext()[Job]
        fun valid() = !closed.get() && epoch.get() == intent && request?.isActive != false
        suspend fun permitted(): Boolean {
            if (!valid()) return false
            val allowed = canCommit()
            return valid() && allowed
        }
        return try { writes.withLock {
            if (!permitted()) return@withLock Result(Outcome.CANCELLED, currentId)
            if (targetId == currentId && certain) return@withLock Result(Outcome.APPLIED, currentId)
            if (!proven) {
                val passed = try { withTimeoutOrNull(PREFLIGHT_MS) { preflight(targetId) } == true }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { false }
                if (!permitted()) return@withLock Result(Outcome.CANCELLED, currentId)
                if (!passed) return@withLock Result(Outcome.FAILED, currentId)
            }
            val previous = currentId
            certain = false
            try {
                // A cancelled request must still remember an RPC acknowledgement
                // before its policy guard stops subsequent dependent writes.
                withContext(NonCancellable) {
                    withTimeout(INSTALL_MS) {
                        val budget = currentCoroutineContext()[Job]
                        install(targetId) { valid() && budget?.isActive != false }
                    }
                }
                if (!permitted()) throw CancellationException("Main selection cancelled")
                currentId = targetId; certain = true; confirmed(targetId)
                Result(Outcome.APPLIED, targetId)
            } catch (_: Exception) {
                if (closed.get()) return@withLock Result(Outcome.CANCELLED, previous)
                val restored = try {
                    withContext(NonCancellable) {
                        withTimeout(COMPENSATION_MS) {
                            val budget = currentCoroutineContext()[Job]
                            install(previous) { !closed.get() && budget?.isActive != false }
                            check(!closed.get() && budget?.isActive != false)
                        }
                    }
                    currentId = previous; certain = true; confirmed(previous)
                    true
                } catch (_: Exception) { false }
                if (!restored) Result(Outcome.UNCERTAIN, previous)
                else Result(if (!valid()) Outcome.CANCELLED else Outcome.FAILED, previous)
            }
        } } catch (_: CancellationException) { Result(Outcome.CANCELLED, currentId) }
    }

    fun invalidate() { epoch.incrementAndGet() }
    override fun close() { closed.set(true); certain = false; invalidate() }

    private companion object {
        const val PREFLIGHT_MS = 15_000L
        const val INSTALL_MS = 15_000L
        const val COMPENSATION_MS = 12_000L
    }
}
