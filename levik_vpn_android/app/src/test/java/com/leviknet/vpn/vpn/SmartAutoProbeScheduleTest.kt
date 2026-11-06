package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartAutoProbeScheduleTest {
    @Test fun `failure confirmation wakes quickly then backs off while offline`() {
        assertEquals(2_000L, SmartAutoProbeSchedule.intervalMs(1, true, false, true))
        assertEquals(10_000L, SmartAutoProbeSchedule.intervalMs(4, true, false, true))
        assertEquals(30_000L, SmartAutoProbeSchedule.intervalMs(8, true, false, true))
    }

    @Test fun `metered traffic reduces steady checks without delaying pending confirmation`() {
        assertEquals(90_000L, SmartAutoProbeSchedule.intervalMs(0, false, false, true))
        assertEquals(30_000L, SmartAutoProbeSchedule.intervalMs(0, false, false, false))
        assertEquals(30_000L, SmartAutoProbeSchedule.intervalMs(0, false, true, true))
    }

    @Test fun `partial provider recovery clears fast offline polling without declaring health`() {
        val partial = SmartRouteMeasurement(listOf(30.0), 4, successfulTargets = setOf("one.example"))
        val rounds = SmartAutoProbeSchedule.failedRounds(1, partial, false)
        assertEquals(0, rounds)
        assertEquals(90_000L, SmartAutoProbeSchedule.intervalMs(rounds, false, false, true))
        assertEquals(2, SmartAutoProbeSchedule.failedRounds(1, partial, true))
        assertEquals(2, SmartAutoProbeSchedule.failedRounds(1, SmartRouteMeasurement(emptyList(), 4), false))
    }
}
