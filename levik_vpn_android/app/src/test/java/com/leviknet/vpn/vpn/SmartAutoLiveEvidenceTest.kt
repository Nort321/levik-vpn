package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.SmartRouteResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoLiveEvidenceTest {
    private val metric = SmartRouteMeasurement(listOf(50.0, 50.0), 2, 100.0, 30.0, successfulTargets = setOf("one", "two"))
    private fun route(suffix: Char) = SmartRouteResource("r1" + suffix.toString().repeat(20), true, 1.0, "healthy", 0.1, 0.9)

    @Test fun `same endpoint with a new cascade path loses old capacity and body proof`() {
        val cache = SmartAutoLiveEvidence(mapOf("a" to metric), mapOf("a" to route('a')), 0L)
        assertTrue(cache.proven("a", 1L))
        assertNotNull(cache.bandwidth("a", 1L))
        assertEquals(setOf("a"), cache.refresh(mapOf("a" to route('b'))))
        assertFalse(cache.proven("a", 2L))
        assertNull(cache.bandwidth("a", 2L))
        assertEquals(route('b').id, cache.routeIds()["a"])
    }

    @Test fun `temporary missing metadata retains the known route until a different identity arrives`() {
        val cache = SmartAutoLiveEvidence(mapOf("a" to metric), mapOf("a" to route('a')), 0L)
        assertTrue(cache.refresh(emptyMap()).isEmpty())
        assertTrue(cache.proven("a", 1L))
        assertTrue(cache.refresh(mapOf("a" to route('a'))).isEmpty())
        assertEquals(setOf("a"), cache.refresh(mapOf("a" to route('b'))))
    }

    @Test fun `partial or failed transfer cannot establish a data proof and proof expires`() {
        val cache = SmartAutoLiveEvidence(emptyMap(), mapOf("a" to route('a')), 0L)
        cache.measuredBandwidth("a", metric.copy(uploadMbps = null), 1L)
        assertFalse(cache.proven("a", 1L))
        cache.verified("a", 2L)
        assertTrue(cache.proven("a", 3L))
        assertFalse(cache.proven("a", SmartAutoLiveEvidence.TTL_MS + 2L))
        cache.verified("a", 10L)
        cache.failed("a")
        assertFalse(cache.proven("a", 11L))
    }
}
