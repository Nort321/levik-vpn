package com.leviknet.vpn.vpn

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerPingerTest {
    @Test
    fun `extracts vnext endpoint`() {
        val outbound = buildJsonObject {
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", "de1.example.com")
                        put("port", 443)
                    })
                })
            })
        }

        assertEquals("de1.example.com" to 443, ServerPinger.extractEndpoint(outbound))
    }

    @Test
    fun `extracts endpoint from nested converter output`() {
        val outbound = buildJsonObject {
            put("settings", buildJsonObject {
                put("server", buildJsonObject {
                    put("endpoint", buildJsonObject {
                        put("address", "nl.example.com")
                        put("port", 8443)
                    })
                })
            })
        }

        assertEquals("nl.example.com" to 8443, ServerPinger.extractEndpoint(outbound))
    }

    @Test
    fun `rejects settings without a usable endpoint`() {
        val outbound = buildJsonObject {
            put("settings", buildJsonObject {
                put("address", "missing-port.example.com")
            })
        }

        assertNull(ServerPinger.extractEndpoint(outbound))
    }

    @Test
    fun `extracts endpoint from hysteria2 outbound with string or int port`() {
        val outboundIntPort = buildJsonObject {
            put("protocol", "hysteria2")
            put("settings", buildJsonObject {
                put("address", "hy2.example.com")
                put("port", 443)
            })
        }
        assertEquals("hy2.example.com" to 443, ServerPinger.extractEndpoint(outboundIntPort))

        val outboundStringPort = buildJsonObject {
            put("protocol", "hysteria2")
            put("settings", buildJsonObject {
                put("server", "hy2-string.example.com")
                put("port", "8443")
            })
        }
        assertEquals("hy2-string.example.com" to 8443, ServerPinger.extractEndpoint(outboundStringPort))
    }
}
