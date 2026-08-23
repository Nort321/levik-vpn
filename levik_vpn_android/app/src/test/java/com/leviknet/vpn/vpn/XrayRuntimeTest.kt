package com.leviknet.vpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayRuntimeTest {
    private val runtime = XrayRuntime(Json)

    @Test
    fun `creates unique safe routing tags while preserving readable names`() {
        val outbounds = buildJsonArray {
            add(outbound("vless", "🇩🇪 Германия"))
            add(outbound("vless", "🇩🇪 Германия"))
            add(outbound("socks", "local"))
            add(outbound("hysteria", "LTE RU"))
        }

        val servers = runtime.prepareServers(outbounds)

        assertEquals(3, servers.size)
        assertEquals("Германия", servers[0].name)
        assertEquals("DE", servers[0].countryCode)
        assertNotEquals(servers[0].tag, servers[1].tag)
        assertNotEquals(servers[0].id, servers[1].id)
        assertEquals(servers.size, servers.map(TunnelServer::id).toSet().size)
        assertTrue(servers.all { it.tag.matches(Regex("[A-Za-z0-9._:-]+")) })
        assertFalse("sendThrough" in servers[0].outbound)
        assertEquals("hysteria", servers[2].outbound.getValue("protocol").toString().trim('"'))
    }

    @Test
    fun `share display labels that look like addresses never become source binds`() {
        val outbounds = buildJsonArray {
            add(outbound("vless", "proxy", sendThrough = "1.2.3.4"))
            add(outbound("vless", "proxy-2", sendThrough = "DE:1"))
            add(outbound("vless", "proxy-3", sendThrough = "origin"))
        }

        val servers = runtime.prepareServers(outbounds, preserveSendThrough = false)

        assertEquals("1.2.3.4", servers[0].name)
        assertEquals("DE:1", servers[1].name)
        assertEquals("origin", servers[2].name)
        assertTrue(servers.all { "sendThrough" !in it.outbound })
    }

    @Test
    fun `full xray config preserves explicit source bind`() {
        val servers = runtime.prepareServers(
            rawOutbounds = buildJsonArray {
                add(outbound("vless", "Germany", sendThrough = "192.0.2.10"))
            },
            preserveSendThrough = true,
        )

        assertEquals(
            "\"192.0.2.10\"",
            servers.single().outbound.getValue("sendThrough").toString(),
        )
    }

    @Test
    fun `stale owner cleanup without a lease cannot stop a newer core`() {
        val ownership = CoreOwnershipRegistry()
        ownership.claim(1)
        ownership.claim(2)

        assertFalse(
            ownership.canStop(
                requestingOwner = 1,
                requestedLease = null,
                activeOwner = 2,
                activeLease = 42,
            ),
        )
        assertTrue(
            ownership.canStop(
                requestingOwner = 2,
                requestedLease = null,
                activeOwner = 2,
                activeLease = 42,
            ),
        )
    }

    @Test
    fun `retired and superseded owners cannot start`() {
        val ownership = CoreOwnershipRegistry()
        ownership.claim(1)
        ownership.retire(1)
        assertFalse(ownership.isCurrent(1))

        ownership.claim(2)
        assertTrue(ownership.isCurrent(2))
        assertFalse(ownership.isCurrent(1))
    }

    @Test
    fun `reality outbounds are adapted to the 26x client schema`() {
        val servers = runtime.prepareServers(
            rawOutbounds = buildJsonArray {
                add(
                    buildJsonObject {
                        put("protocol", "vless")
                        put("tag", "legacy-array")
                        put("settings", buildJsonObject {
                            put("vnext", buildJsonArray {
                                add(buildJsonObject {
                                    put("address", "de1.example.com")
                                    put("port", 443)
                                })
                            })
                        })
                        put("streamSettings", buildJsonObject {
                            put("network", "tcp")
                            put("security", "reality")
                            put("realitySettings", buildJsonObject {
                                put("publicKey", "pbk-value")
                                put("shortId", "abcd")
                                put("serverNames", buildJsonArray {
                                    add("www.example.com")
                                    add("extra.example.com")
                                })
                            })
                        })
                    },
                )
                add(
                    buildJsonObject {
                        put("protocol", "vless")
                        put("tag", "missing-sni")
                        put("settings", buildJsonObject {
                            put("vnext", buildJsonArray {
                                add(buildJsonObject {
                                    put("address", "de2.example.com")
                                    put("port", 443)
                                })
                            })
                        })
                        put("streamSettings", buildJsonObject {
                            put("network", "tcp")
                            put("security", "reality")
                            put("realitySettings", buildJsonObject {
                                put("publicKey", "pbk-value")
                                put("shortId", "abcd")
                                put("serverNames", buildJsonArray {})
                            })
                        })
                    },
                )
                add(
                    buildJsonObject {
                        put("protocol", "vless")
                        put("tag", "null-server-fields")
                        put("settings", buildJsonObject {})
                        put("streamSettings", buildJsonObject {
                            put("network", "tcp")
                            put("security", "reality")
                            put("realitySettings", buildJsonObject {
                                put("target", JsonNull)
                                put("dest", JsonNull)
                                put("privateKey", "")
                                put("publicKey", "pbk-value")
                                put("serverNames", buildJsonArray { add("www.example.com") })
                            })
                        })
                    },
                )
            },
        )

        // Legacy serverNames array collapses into singular serverName.
        val legacyReality = servers[0].realitySettings()
        assertEquals("www.example.com", legacyReality.serverNameText())
        assertTrue("serverNames" !in legacyReality)

        // Missing sni falls back to the endpoint domain, stored as serverName.
        val repairedReality = servers[1].realitySettings()
        assertEquals("de2.example.com", repairedReality.serverNameText())
        assertTrue("serverNames" !in repairedReality)

        // Null server-only converter fields must not switch Xray into server mode.
        val cleanedReality = servers[2].realitySettings()
        assertEquals("www.example.com", cleanedReality.serverNameText())
        assertTrue("serverNames" !in cleanedReality)
        assertTrue("target" !in cleanedReality)
        assertTrue("dest" !in cleanedReality)
        assertTrue("privateKey" !in cleanedReality)
    }

    private fun TunnelServer.realitySettings(): kotlinx.serialization.json.JsonObject =
        (outbound.getValue("streamSettings") as kotlinx.serialization.json.JsonObject)
            .getValue("realitySettings") as kotlinx.serialization.json.JsonObject

    private fun kotlinx.serialization.json.JsonObject.serverNameText(): String =
        getValue("serverName").jsonPrimitive.content

    private fun outbound(
        protocol: String,
        tag: String,
        sendThrough: String? = null,
    ) = buildJsonObject {
        put("protocol", protocol)
        put("tag", tag)
        sendThrough?.let { put("sendThrough", it) }
            ?: tag.takeIf { it.startsWith("🇩🇪") }?.let { put("sendThrough", it) }
        put("settings", buildJsonObject {})
    }
}
