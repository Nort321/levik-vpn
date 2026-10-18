package com.leviknet.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelFreezeDetectorTest {
    private val kb = 1024L

    @Test
    fun `uploads without answers over a window raise the alarm`() {
        val detector = TunnelFreezeDetector()
        assertFalse(detector.record(rxBytes = 100 * kb, txBytes = 10 * kb, nowMs = 0))
        assertFalse(detector.record(rxBytes = 102 * kb, txBytes = 30 * kb, nowMs = 6_000))
        assertTrue(detector.record(rxBytes = 104 * kb, txBytes = 50 * kb, nowMs = 12_000))
    }

    @Test
    fun `ordinary browsing and idle tunnels are fine`() {
        val detector = TunnelFreezeDetector()
        detector.record(rxBytes = 0, txBytes = 0, nowMs = 0)
        assertFalse(detector.record(rxBytes = 900 * kb, txBytes = 60 * kb, nowMs = 12_000))
        // Too little upload to judge.
        assertFalse(detector.record(rxBytes = 900 * kb, txBytes = 70 * kb, nowMs = 24_000))
    }

    @Test
    fun `one alarm per cooldown and a counter reset starts over`() {
        val detector = TunnelFreezeDetector()
        detector.record(rxBytes = 0, txBytes = 0, nowMs = 0)
        assertTrue(detector.record(rxBytes = kb, txBytes = 40 * kb, nowMs = 12_000))
        detector.record(rxBytes = kb, txBytes = 40 * kb, nowMs = 13_000)
        assertFalse(detector.record(rxBytes = 2 * kb, txBytes = 90 * kb, nowMs = 26_000))
        // A new connection restarts the counters; the old samples must not count.
        detector.reset()
        assertFalse(detector.record(rxBytes = 0, txBytes = 0, nowMs = 30_000))
        assertTrue(detector.record(rxBytes = kb, txBytes = 40 * kb, nowMs = 42_000))
    }

    @Test
    fun `each false alarm doubles the wait for the next one, up to a limit`() {
        val detector = TunnelFreezeDetector(cooldownMs = 60_000, maxCooldownMs = 4 * 60_000)
        var now = 0L
        // Twelve seconds of uploads that get almost nothing back.
        fun upload(): Boolean {
            val first = detector.record(now / 1_000, now * 4, now)
            now += 12_000
            return detector.record(now / 1_000, now * 4, now) || first
        }
        assertTrue(upload())
        detector.checkPassed() // the next alarm waits 2 minutes
        now += 60_000
        assertFalse(upload())
        now += 60_000
        assertTrue(upload())
        detector.checkPassed() // 4 minutes, the limit
        detector.checkPassed()
        now += 3 * 60_000
        assertFalse(upload())
        now += 60_000
        assertTrue(upload())
        // A new connection starts from the shortest wait again.
        detector.reset()
        assertTrue(upload())
        now += 120_000
        assertTrue(upload())
    }
}
