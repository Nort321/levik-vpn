package com.leviknet.vpn.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveLoadBackoffTest {
    @Test fun `retry after supports seconds dates and bounded untrusted values`() {
        val now = java.time.Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
        assertEquals(30_000L, LiveLoadPolicy.retryAfterMs("30", now))
        assertEquals(60_000L, LiveLoadPolicy.retryAfterMs("Fri, 6 Nov 2026 12:01:00 GMT", now))
        assertEquals(1_000L, LiveLoadPolicy.retryAfterMs("0", now))
        assertEquals(3_600_000L, LiveLoadPolicy.retryAfterMs(Long.MAX_VALUE.toString(), now))
        assertEquals(60_000L, LiveLoadPolicy.retryAfterMs("invalid", now))
        assertEquals(60_000L, LiveLoadPolicy.retryAfterMs(null, now))
        assertEquals(60_000L, LiveLoadPolicy.retryAfterMs("-5", now))
    }

    @Test fun `rate limiting never refreshes stale resource evidence`() {
        val previous = LiveLoad(emptyMap(), emptySet(), emptyMap(), 1000L, null, null)
        assertSame(previous, LiveLoadPolicy.merge(previous, LiveLoadAnswer.RetryLater(60_000), 20_000L, false))
    }
}
