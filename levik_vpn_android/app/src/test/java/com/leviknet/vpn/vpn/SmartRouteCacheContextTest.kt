package com.leviknet.vpn.vpn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SmartRouteCacheContextTest {
    private fun server(secret: String = "first", protocol: String = "vless") = TunnelServer("stable-id", "stable-tag", "Node", "DE",
        JsonObject(mapOf("protocol" to JsonPrimitive(protocol), "settings" to JsonObject(mapOf("id" to JsonPrimitive(secret))))))

    @Test fun `same server id with changed credentials or protocol invalidates transport context`() {
        val before = SmartRouteCacheContext.fingerprint(server(), listOf("false", "dns-a"))
        assertEquals(before, SmartRouteCacheContext.fingerprint(server(), listOf("false", "dns-a")))
        assertNotEquals(before, SmartRouteCacheContext.fingerprint(server(secret = "renewed"), listOf("false", "dns-a")))
        assertNotEquals(before, SmartRouteCacheContext.fingerprint(server(protocol = "hysteria2"), listOf("false", "dns-a")))
    }

    @Test fun `anti DPI DNS and framing changes invalidate the cached context`() {
        val before = SmartRouteCacheContext.fingerprint(server(), listOf("false", "dns-a"))
        assertNotEquals(before, SmartRouteCacheContext.fingerprint(server(), listOf("true", "dns-a")))
        assertNotEquals(before, SmartRouteCacheContext.fingerprint(server(), listOf("false", "dns-b")))
        assertNotEquals(SmartRouteCacheContext.fingerprint(server(), listOf("a", "bc")),
            SmartRouteCacheContext.fingerprint(server(), listOf("ab", "c")))
    }

    @Test fun `native TUIC credential changes invalidate the same stable endpoint id`() {
        val tuic = TuicServerConfig("example.net", 443, "uuid", "first", "example.net", listOf("h3"), "bbr", "native", "test-ca")
        val before = server().copy(engine = TunnelEngineKind.LEVIK_TUIC, tuicConfig = tuic)
        val after = before.copy(tuicConfig = tuic.copy(password = "renewed"))
        assertNotEquals(SmartRouteCacheContext.fingerprint(before, emptyList()), SmartRouteCacheContext.fingerprint(after, emptyList()))
    }
}
