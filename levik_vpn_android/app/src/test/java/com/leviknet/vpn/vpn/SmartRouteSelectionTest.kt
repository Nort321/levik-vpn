package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.SmartRouteResource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRouteSelectionTest {
    private fun measurement(delay: Double, down: Double? = 100.0, up: Double? = 30.0) =
        SmartRouteMeasurement(List(6) { delay }, 6, down, up, successfulTargets = setOf("speed.cloudflare.com", "proof.ovh.net"))
    private fun resource(complete: Boolean = true, age: Double = 1.0, state: String = "healthy", load: Double = 0.2) =
        SmartRouteResource("r1" + "a".repeat(20), complete, age, state, load, 1.0 - load)

    @Test fun `actual cascade response beats neither entry SYN nor country heuristics`() {
        val measured = mapOf("moscow-cascade" to measurement(180.0), "direct" to measurement(55.0))
        assertEquals("direct", SmartRouteSelection.order(measured, emptyMap()).first())
    }

    @Test fun `a severely throttled low delay route loses to a fast usable route`() {
        val measured = mapOf("near-slow" to measurement(20.0, 3.0, 1.0), "far-fast" to measurement(60.0, 100.0, 30.0))
        assertEquals("far-fast", SmartRouteSelection.order(measured, emptyMap()).first())
    }

    @Test fun `delay wins between routes within ten percent of balanced bandwidth`() {
        val measured = mapOf("fast-ping" to measurement(45.0, 92.0, 28.0), "big-channel" to measurement(90.0, 100.0, 30.0))
        assertEquals("fast-ping", SmartRouteSelection.order(measured, emptyMap()).first())
    }

    @Test fun `fresh overload cannot win but stale or missing resource status remains eligible`() {
        val measured = mapOf("a" to measurement(20.0), "b" to measurement(60.0))
        assertEquals("b", SmartRouteSelection.order(measured, mapOf("a" to resource(state = "overloaded", load = 1.0))).first())
        assertEquals("a", SmartRouteSelection.order(measured, mapOf("a" to resource(age = 46.0, state = "overloaded", load = 1.0))).first())
        assertEquals("a", SmartRouteSelection.order(measured, mapOf("a" to resource(complete = false))).first())
        assertTrue(resource(complete = false, state = "overloaded").overloaded)
        assertFalse(resource(complete = false).fresh)
    }

    @Test fun `missing bandwidth falls back to real path delay when no complete test exists`() {
        assertEquals("b", SmartRouteSelection.order(mapOf("a" to measurement(90.0, null), "b" to measurement(40.0, null)), emptyMap()).first())
        assertEquals("a", SmartRouteSelection.order(mapOf("a" to measurement(90.0), "b" to measurement(40.0, null)), emptyMap()).first())
    }

    @Test fun `missing small responses carry a failure penalty`() {
        val unstable = SmartRouteMeasurement(listOf(10.0, 10.0), 6)
        assertEquals("stable", SmartRouteSelection.order(mapOf("unstable" to unstable, "stable" to measurement(60.0, null)), emptyMap()).first())
    }

    @Test fun `one provider is partial evidence and cannot displace a confirmed path`() {
        val partial = measurement(20.0).copy(successfulTargets = setOf("speed.cloudflare.com"))
        assertFalse(partial.confirmed)
        assertEquals(10_000L, partial.cacheMs)
        assertEquals("confirmed", SmartRouteSelection.order(mapOf("partial" to partial, "confirmed" to measurement(60.0)), emptyMap()).first())
        assertEquals("partial", SmartRouteSelection.order(mapOf("partial" to partial), emptyMap()).first())
    }

    @Test fun `a measured overloaded route cannot displace an unmeasured unknown alternative`() {
        val measured = mapOf("full" to measurement(20.0))
        val resources = mapOf("full" to resource(state = "overloaded", load = 1.0))
        val ids = listOf("full", "unknown")
        assertTrue(SmartRouteSelection.order(measured, resources, ids).isEmpty())
        assertEquals(setOf("unknown"), SmartRouteSelection.eligibleIds(ids, resources))
        assertEquals("unknown", SmartRouteSelection.select(measured, resources, ids, "full"))
    }

    @Test fun `all overloaded still leaves a route to try`() {
        val resources = mapOf("a" to resource(state = "overloaded"), "b" to resource(state = "overloaded"))
        assertEquals(setOf("a", "b"), SmartRouteSelection.eligibleIds(listOf("a", "b"), resources))
        assertEquals("a", SmartRouteSelection.select(mapOf("a" to measurement(40.0)), resources, listOf("a", "b"), "b"))
    }

    @Test fun `many shared Moscow entries cannot consume the entire shortlist`() {
        fun server(id: String, host: String) = TunnelServer(id, id, id, "DE", JsonObject(mapOf("settings" to JsonObject(mapOf(
            "address" to JsonPrimitive(host), "port" to JsonPrimitive(443),
        )))))
        val list = (0 until 40).map { server("relay-$it", "moscow.example") } + server("direct", "direct.example")
        val candidates = SmartRouteSelection.candidates(list)
        assertEquals(32, candidates.size)
        assertEquals("direct", candidates[1].id)
    }
}
