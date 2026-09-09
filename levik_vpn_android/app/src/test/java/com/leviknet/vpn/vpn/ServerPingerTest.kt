package com.leviknet.vpn.vpn

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    @Test
    fun `QUIC probe uses a reserved version and validates echoed connection IDs`() {
        val probe = ServerPinger.buildQuicProbePacket()
        assertEquals(1200, probe.size)
        assertTrue((1..4).all { probe[it] == 0x0A.toByte() })
        val response = ByteArray(27)
        response[0] = 0x80.toByte()
        response[5] = 8
        response[14] = 8
        probe.copyInto(response, 6, 15, 23)
        probe.copyInto(response, 15, 6, 14)
        response[26] = 1
        assertTrue(ServerPinger.isQuicVersionNegotiation(probe, response))
        assertFalse(ServerPinger.isQuicVersionNegotiation(probe, response.copyOf(26)))
        assertFalse(ServerPinger.isQuicVersionNegotiation(probe, ByteArray(27)))
        assertFalse(ServerPinger.isQuicVersionNegotiation(probe, response.copyOf().apply { this[1] = 1 }))
        assertFalse(ServerPinger.isQuicVersionNegotiation(probe, response.copyOf().apply { this[6] = (this[6].toInt() xor 1).toByte() }))
        val invalidVersions = response.copyOf(31)
        probe.copyInto(invalidVersions, 27, 1, 5)
        assertFalse(ServerPinger.isQuicVersionNegotiation(probe, invalidVersions))
    }

    @Test
    fun `UDP measurement never bypasses missing VPN datagram protection`() {
        val owner = Long.MAX_VALUE
        ServerPinger.registerSocketProtector(owner) { true }
        try {
            assertNull(ServerPinger.measure(buildJsonObject {
                put("protocol", "hysteria2")
                put("settings", buildJsonObject { put("address", "127.0.0.1"); put("port", 443) })
            }))
        } finally {
            ServerPinger.unregisterSocketProtector(owner)
        }
    }

}
