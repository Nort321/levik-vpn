package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.SmartRouteResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartAutoLivePolicyTest {
    private fun metric(delay: Double, down: Double = 100.0, up: Double = 50.0) = SmartRouteMeasurement(
        List(6) { delay }, 6, down, up, successfulTargets = setOf("a.example", "b.example"),
    )

    @Test fun `a normal better path needs two consecutive confirmed wins`() {
        val policy = SmartAutoLivePolicy()
        val values = mapOf("a" to metric(100.0), "b" to metric(70.0))
        assertNull(policy.consider("a", values, emptyMap(), 0L))
        assertEquals("b", policy.consider("a", values, emptyMap(), 30_000L))
    }

    @Test fun `near equal and unknown partial incumbent stay pinned`() {
        val policy = SmartAutoLivePolicy()
        repeat(3) { round -> assertNull(policy.consider("a", mapOf("a" to metric(50.0), "b" to metric(45.0)), emptyMap(), round * 30_000L)) }
        val partial = metric(200.0).copy(successfulTargets = setOf("a.example"))
        repeat(3) { round -> assertNull(policy.consider("a", mapOf("a" to partial, "b" to metric(40.0)), emptyMap(), round * 30_000L)) }
    }

    @Test fun `cooldown holds normal gains while two fresh full rounds may bypass it`() {
        val policy = SmartAutoLivePolicy()
        policy.installed(0L)
        val values = mapOf("a" to metric(100.0), "b" to metric(50.0))
        assertNull(policy.consider("a", values, emptyMap(), 10_000L))
        assertNull(policy.consider("a", values, emptyMap(), 40_000L))
        val full = SmartRouteResource("r1" + "a".repeat(20), true, 1.0, "overloaded", 1.0, 0.0)
        assertNull(policy.consider("a", values, mapOf("a" to full), 70_000L))
        assertEquals("b", policy.consider("a", values, mapOf("a" to full), 100_000L))
    }

    @Test fun `two failed current rounds permit a confirmed working alternative`() {
        val policy = SmartAutoLivePolicy()
        policy.installed(0L)
        val values = mapOf("a" to SmartRouteMeasurement(emptyList(), 2), "b" to metric(100.0))
        assertNull(policy.consider("a", values, emptyMap(), 10_000L))
        assertEquals("b", policy.consider("a", values, emptyMap(), 40_000L))
    }

    @Test fun `activity freeze clears accumulated candidate wins`() {
        val policy = SmartAutoLivePolicy()
        val values = mapOf("a" to metric(100.0), "b" to metric(60.0))
        assertNull(policy.consider("a", values, emptyMap(), 0L))
        policy.pause()
        assertNull(policy.consider("a", values, emptyMap(), 90_000L))
        assertEquals("b", policy.consider("a", values, emptyMap(), 120_000L))
    }

    @Test fun `balanced throughput can improve despite a small uplink loss`() {
        val policy = SmartAutoLivePolicy()
        val values = mapOf("a" to metric(60.0, 10.0, 100.0), "b" to metric(65.0, 100.0, 90.0))
        assertNull(policy.consider("a", values, emptyMap(), 0L))
        assertEquals("b", policy.consider("a", values, emptyMap(), 30_000L))
    }

    @Test fun `partial challenger never wins even when current is overloaded`() {
        val policy = SmartAutoLivePolicy()
        val values = mapOf("a" to metric(100.0), "b" to metric(20.0).copy(successfulTargets = setOf("one")))
        val full = SmartRouteResource("r1" + "a".repeat(20), true, 1.0, "overloaded", 1.0, 0.0)
        repeat(4) { assertNull(policy.consider("a", values, mapOf("a" to full), it * 30_000L)) }
    }
}
