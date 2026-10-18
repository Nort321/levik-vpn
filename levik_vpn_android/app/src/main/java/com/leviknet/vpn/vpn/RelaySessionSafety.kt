package com.leviknet.vpn.vpn

import java.time.Duration
import java.time.Instant

internal fun relayCredentialHasSameBinding(previous: RelayServerConfig, next: RelayServerConfig): Boolean =
    previous.node.id == next.node.id &&
        previous.bootstrap.deviceId == next.bootstrap.deviceId &&
        previous.bootstrap.entitlementId == next.bootstrap.entitlementId &&
        previous.bootstrap.version == next.bootstrap.version &&
        previous.bootstrap.protocol == next.bootstrap.protocol &&
        previous.bootstrap.policy == next.bootstrap.policy

internal fun relayCredentialExtendsAccess(previous: RelayServerConfig, next: RelayServerConfig): Boolean =
    relayCredentialHasSameBinding(previous, next) && runCatching {
        Instant.parse(next.bootstrap.expiresAt).isAfter(Instant.parse(previous.bootstrap.expiresAt))
    }.getOrDefault(false)

internal fun relayCredentialRenewalDue(remainingMillis: Long): Boolean = remainingMillis in 1..300_000L

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
