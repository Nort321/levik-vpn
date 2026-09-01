package com.leviknet.vpn.vpn

import java.time.Duration
import java.time.Instant

/**
 * Converts an absolute credential expiry once and then relies only on elapsed realtime. A later
 * wall-clock rollback therefore cannot extend the native relay/TUN lifetime.
 */
internal class MonotonicCredentialDeadline private constructor(
    val deadlineElapsedMs: Long,
) {
    fun remainingMillis(nowElapsedMs: Long): Long =
        (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    fun isExpired(nowElapsedMs: Long): Boolean = nowElapsedMs >= deadlineElapsedMs

    companion object {
        fun create(
            expiresAt: Instant,
            wallClockNow: Instant,
            elapsedRealtimeMs: Long,
        ): MonotonicCredentialDeadline {
            require(elapsedRealtimeMs >= 0L)
            val remaining = runCatching {
                Duration.between(wallClockNow, expiresAt).toMillis()
            }.getOrElse { Long.MAX_VALUE }
            require(remaining > 0L) { "Relay credential has expired" }
            val deadline = if (Long.MAX_VALUE - elapsedRealtimeMs < remaining) {
                Long.MAX_VALUE
            } else {
                elapsedRealtimeMs + remaining
            }
            return MonotonicCredentialDeadline(deadline)
        }
    }
}
