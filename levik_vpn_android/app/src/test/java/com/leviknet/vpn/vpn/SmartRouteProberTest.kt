package com.leviknet.vpn.vpn

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SmartRouteProberTest {
    private val candidates = (0..2).map { SmartRouteProbeConfig.Candidate("r$it", "r$it", "/unused-$it") }

    @Test fun `background RTT rounds stay tiny and failed data body cannot verify a candidate`() = runTest {
        var downloaded = 0
        val transport = SmartRouteProbeTransport { _, _, bytes, _ ->
            downloaded += bytes
            if (bytes >= 64 * 1024) null else SmartRouteExchange(40.0, 100.0)
        }
        SmartRouteProber(candidates, transport).use { prober ->
            prober.measureLatency(light = true) { _, _ -> }
            assertEquals(3 * 6 * 1024, downloaded)
            assertTrue(!prober.verifyData(candidates.first().id))
        }
    }

    @Test fun `cancelling selection cancels pending path transfers without publishing stale answers`() = runTest {
        var completed = 0
        var cancelled = 0
        val transport = SmartRouteProbeTransport { _, _, _, _ ->
            try { delay(100_000); SmartRouteExchange(40.0, 100.0) } finally { cancelled++ }
        }
        SmartRouteProber(candidates, transport).use { prober ->
            val job = launch { prober.measureLatency { _, _ -> completed++ } }
            advanceTimeBy(10)
            job.cancelAndJoin()
        }
        assertEquals(0, completed)
        assertEquals(3, cancelled)
    }

    @Test fun `finalist speed transfers are sequential and await both directions`() = runTest {
        var active = 0
        var maxActive = 0
        var uploaded = 0
        val transport = SmartRouteProbeTransport { _, _, _, upload ->
            active++
            maxActive = maxOf(maxActive, active)
            delay(10)
            uploaded += upload
            active--
            SmartRouteExchange(40.0, 100.0)
        }
        val measured = mutableMapOf<String, SmartRouteMeasurement>()
        SmartRouteProber(candidates, transport).use { prober ->
            prober.measureBandwidth(candidates.associate { it.id to SmartRouteMeasurement(listOf(40.0, 40.0), 2) }) { id, value -> measured[id] = value }
        }
        assertEquals(1, maxActive)
        assertEquals(3 * 128 * 1024, uploaded)
        assertTrue(measured.values.all { it.hasBandwidth })
    }

    @Test fun `unreachable routes retain unknown speed and stop after two failed targets`() = runTest {
        var calls = 0
        val measured = mutableMapOf<String, SmartRouteMeasurement>()
        SmartRouteProber(candidates, SmartRouteProbeTransport { _, _, _, _ -> calls++; null }).use { prober ->
            prober.measureLatency { id, value -> measured[id] = value }
        }
        assertEquals(6, calls)
        assertTrue(measured.values.none { it.delayCost.isFinite() || it.hasBandwidth })
    }

    @Test fun `blackholed first entries cannot starve later independent routes`() = runTest {
        val all = (0..11).map { SmartRouteProbeConfig.Candidate("r$it", "r$it", "/unused-$it") }
        val measured = mutableMapOf<String, SmartRouteMeasurement>()
        SmartRouteProber(all, SmartRouteProbeTransport { id, _, _, _ ->
            if (id.removePrefix("r").toInt() < 8) { delay(10_000); null } else SmartRouteExchange(40.0, 100.0)
        }).use { prober -> prober.measureLatency { id, value -> measured[id] = value } }
        assertTrue((8..11).all { measured["r$it"]?.samplesMs?.size == 6 })
    }

    @Test fun `repeated Cloudflare successes never manufacture independent provider evidence`() = runTest {
        val measured = mutableMapOf<String, SmartRouteMeasurement>()
        val transport = SmartRouteProbeTransport { _, request, _, _ ->
            if (request.url.host == "speed.cloudflare.com") SmartRouteExchange(30.0, 50.0) else null
        }
        SmartRouteProber(candidates, transport).use { prober ->
            prober.measureLatency { id, value -> measured[id] = value }
            prober.measureBandwidth(measured.toMap()) { id, value -> measured[id] = value }
        }
        assertTrue(measured.values.all { it.hasBandwidth && !it.confirmed && it.successfulTargets.size == 1 })
    }
}
