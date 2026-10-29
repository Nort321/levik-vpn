package com.leviknet.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoActivityGuardTest {
    private fun counters(total: Long) = SmartAutoTrafficCounters(total, total)
    private fun quiet(guard: SmartAutoActivityGuard, start: Long = 0L, initial: Long = 0L) {
        for (offset in 0L..65_000L step 5_000L) guard.observe(counters(initial), start + offset)
    }

    @Test fun `missing samples never authorize a move and quiet requires sixty seconds`() {
        val guard = SmartAutoActivityGuard()
        assertFalse(guard.allowed(0L))
        for (at in 0L..60_000L step 5_000L) guard.observe(counters(0), at)
        assertFalse(guard.allowed(60_000L))
        guard.observe(counters(0), 65_000L)
        assertTrue(guard.allowed(65_000L))
    }

    @Test fun `small background keepalives do not prevent a quiet window`() {
        val guard = SmartAutoActivityGuard()
        for (at in 0L..65_000L step 5_000L) guard.observe(counters(at / 2), at)
        assertTrue(guard.allowed(65_000L))
    }

    @Test fun `moderate traffic resets the full quiet window`() {
        val guard = SmartAutoActivityGuard()
        quiet(guard)
        guard.observe(counters(15_000L), 70_000L)
        assertFalse(guard.allowed(70_000L))
        for (at in 75_000L..130_000L step 5_000L) guard.observe(counters(15_000L), at)
        assertFalse(guard.allowed(130_000L))
        guard.observe(counters(15_000L), 135_000L)
        assertTrue(guard.allowed(135_000L))
    }

    @Test fun `new burst immediately before installing a target disarms an idle decision`() {
        val guard = SmartAutoActivityGuard()
        quiet(guard)
        assertTrue(guard.allowed(65_000L))
        guard.observe(counters(64 * 1024L), 66_000L)
        assertFalse(guard.allowed(66_000L))
    }

    @Test fun `stale failed and reset counters fail closed`() {
        val guard = SmartAutoActivityGuard()
        quiet(guard, initial = 100_000L)
        assertFalse(guard.allowed(75_001L))
        guard.observe(null, 76_000L)
        assertFalse(guard.allowed(76_000L))
        quiet(guard, 80_000L, 100_000L)
        assertTrue(guard.allowed(145_000L))
        // The aggregate grows, but one direction resets: still unsafe.
        guard.observe(SmartAutoTrafficCounters(99_999L, 200_000L), 150_000L)
        assertFalse(guard.allowed(150_000L))
    }
}
