package com.leviknet.vpn.core.platform

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRouteResourceTest {
    private val node = "n1" + "1".repeat(20)
    private val route = "r1" + "a".repeat(20)

    @Test fun `v2 parser accepts only bounded numeric route fields and opaque ids`() {
        val parsed = SmartRouteResource.parse(Json.parseToJsonElement("""{"$node":{"id":"$route","complete":false,"ageSeconds":2,"state":"unknown","resourceLoad":0.4,"resourceHeadroom":null},"bad":{"id":"$route"}}"""))
        assertEquals(1, parsed.size)
        assertFalse(parsed.getValue(node).fresh)
        assertNull(parsed.getValue(node).resourceHeadroom)
        val invalid = SmartRouteResource.parse(Json.parseToJsonElement("""{"$node":{"id":"$route","complete":true,"ageSeconds":"2","state":"healthy","resourceLoad":"0.1"}}"""))
        assertNull(invalid.getValue(node).ageSeconds)
        assertNull(invalid.getValue(node).resourceLoad)
    }

    @Test fun `304 retrieval does not renew the age of the actual agent report`() {
        val status = SmartRouteResource(route, true, 35.0, "healthy", 0.2, 0.8)
        val before = LiveLoad(emptyMap(), emptySet(), emptyMap(), 1000L, null, null, mapOf(node to status))
        val unchanged = LiveLoadPolicy.merge(before, LiveLoadAnswer.Same, 21000L, false)!!
        assertEquals(55.0, unchanged.routes.getValue(node).ageSeconds!!, 0.0)
        assertFalse(LiveLoadPolicy.view(unchanged, null, 22000L).routes.getValue(node).fresh)
    }

    @Test fun `fresh known overloaded hop remains unsafe with another hop missing`() {
        val known = SmartRouteResource(route, false, 1.0, "overloaded", null, null)
        assertTrue(known.overloaded)
        assertFalse(known.copy(ageSeconds = 46.0).overloaded)
    }
}
