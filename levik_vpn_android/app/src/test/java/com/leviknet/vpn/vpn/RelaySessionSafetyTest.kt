package com.leviknet.vpn.vpn

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySessionSafetyTest {
    @Test
    fun `credential deadline cannot be extended by wall clock rollback`() {
        val wallNow = Instant.parse("2026-08-31T10:00:00Z")
        val deadline = MonotonicCredentialDeadline.create(
            expiresAt = wallNow.plusSeconds(60),
            wallClockNow = wallNow,
            elapsedRealtimeMs = 5_000L,
        )

        // Wall time is deliberately irrelevant after creation; only elapsed realtime advances.
        assertFalse(deadline.isExpired(64_999L))
        assertEquals(1L, deadline.remainingMillis(64_999L))
        assertTrue(deadline.isExpired(65_000L))
        assertEquals(0L, deadline.remainingMillis(65_001L))
    }

    @Test
    fun `already expired credential is rejected before native startup`() {
        val now = Instant.parse("2026-08-31T10:00:00Z")

        assertThrows(IllegalArgumentException::class.java) {
            MonotonicCredentialDeadline.create(
                expiresAt = now,
                wallClockNow = now,
                elapsedRealtimeMs = 100L,
            )
        }
    }
}
