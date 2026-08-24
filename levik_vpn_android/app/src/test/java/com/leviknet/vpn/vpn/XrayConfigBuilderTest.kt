package com.leviknet.vpn.vpn

import com.leviknet.vpn.data.RoutingPreset
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {
    private val json = Json
    private val builder = XrayConfigBuilder(
        json = json,
        clock = Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `builds app owned tun config and puts selected outbound first`() {
        val first = server("a".repeat(64), "server-a")
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(first, selected),
            directCidrs = listOf("192.0.2.0/24"),
        )

        val config = json.parseToJsonElement(
            builder.build(profile, selected.id, 42),
        ).jsonObject
        val inbound = config.getValue("inbounds").jsonArray.single().jsonObject
        val outbounds = config.getValue("outbounds").jsonArray

        assertEquals("42", config.getValue("env").jsonObject
            .getValue("xray.tun.fd").jsonPrimitive.content)
        assertEquals("tun", inbound.getValue("protocol").jsonPrimitive.content)
        assertTrue("mtu" in inbound.getValue("settings").jsonObject)
        assertFalse("MTU" in inbound.getValue("settings").jsonObject)
        assertEquals("server-b", outbounds[0].jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals("freedom", outbounds[outbounds.size - 2].jsonObject
            .getValue("protocol").jsonPrimitive.content)
        assertEquals("blackhole", outbounds.last().jsonObject
            .getValue("protocol").jsonPrimitive.content)
        assertFalse("api" in config)
        assertFalse("metrics" in config)
    }

    @Test
    fun `routes Russian domains and addresses directly when bypass is enabled`() {
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val config = json.parseToJsonElement(
            builder.build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = 42,
                bypassRussianTraffic = true,
                russianDirectCidrs = listOf("203.0.113.0/24"),
            ),
        ).jsonObject
        val routing = config.getValue("routing").jsonObject
        val rules = routing.getValue("rules").jsonArray

        assertEquals("IPIfNonMatch", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertTrue(rules.first().jsonObject.getValue("domain").jsonArray.any {
            it.jsonPrimitive.content == "domain:ru"
        })
        assertTrue(rules[1].jsonObject.getValue("ip").jsonArray.any {
            it.jsonPrimitive.content == "203.0.113.0/24"
        })
    }

    @Test
    fun `does not add Russian routes when bypass is disabled`() {
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val config = json.parseToJsonElement(
            builder.build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = 42,
                routingPreset = RoutingPreset.GLOBAL,
                bypassRussianTraffic = false,
                russianDirectCidrs = listOf("203.0.113.0/24"),
            ),
        ).jsonObject
        val routing = config.getValue("routing").jsonObject
        val rules = routing.getValue("rules").jsonArray

        assertEquals("AsIs", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertTrue(rules.none { "domain" in it.jsonObject })
        assertTrue(rules.single().jsonObject.getValue("ip").jsonArray.none {
            it.jsonPrimitive.content == "203.0.113.0/24"
        })
    }

    @Test
    fun `repairs cached reality outbounds missing server names before building`() {
        val selected = realityServer("c".repeat(64), "server-c")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val config = json.parseToJsonElement(
            builder.build(profile, selected.id, 42),
        ).jsonObject
        val outbound = config.getValue("outbounds").jsonArray.first().jsonObject
        val realitySettings = outbound.getValue("streamSettings").jsonObject
            .getValue("realitySettings").jsonObject

        assertEquals(
            "de1.example.com",
            realitySettings.getValue("serverName").jsonPrimitive.content,
        )
        assertTrue("serverNames" !in realitySettings)
        assertTrue("target" !in realitySettings)
        assertTrue("dest" !in realitySettings)
        assertTrue("privateKey" !in realitySettings)
    }

    @Test
    fun `rejects reality outbounds that stay without server names`() {
        val selected = realityServer("d".repeat(64), "server-d", address = "203.0.113.5")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val error = runCatching { builder.build(profile, selected.id, 42) }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("REALITY serverName is empty") == true)
        assertTrue(error?.message?.contains("\"security\":\"reality\"") == true)
    }

    @Test
    fun `injects custom DNS and custom routing rules`() {
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val config = json.parseToJsonElement(
            builder.build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = 42,
                primaryDnsIp = "9.9.9.9",
                secondaryDnsIp = "149.112.112.112",
                customDirectDomains = setOf("local.dev"),
                customProxyDomains = setOf("vpn-only.com"),
            ),
        ).jsonObject

        val dns = config.getValue("dns").jsonObject
        val servers = dns.getValue("servers").jsonArray
        assertEquals("9.9.9.9", servers[0].jsonPrimitive.content)
        assertEquals("149.112.112.112", servers[1].jsonPrimitive.content)

        val routing = config.getValue("routing").jsonObject
        val rules = routing.getValue("rules").jsonArray
        val customDirectRule = rules.firstOrNull { rule ->
            rule.jsonObject["outboundTag"]?.jsonPrimitive?.content == "levik-direct" &&
                rule.jsonObject["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "domain:local.dev" } == true
        }
        val customProxyRule = rules.firstOrNull { rule ->
            rule.jsonObject["outboundTag"]?.jsonPrimitive?.content == "server-b" &&
                rule.jsonObject["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "domain:vpn-only.com" } == true
        }

        assertTrue(customDirectRule != null)
        assertTrue(customProxyRule != null)
    }

    @Test
    fun `injects TLS fragment dialer when antiDpi is enabled`() {
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val config = json.parseToJsonElement(
            builder.build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = 42,
                antiDpiEnabled = true,
                dohEndpoint = "https://1.1.1.1/dns-query",
            ),
        ).jsonObject

        val outbounds = config.getValue("outbounds").jsonArray
        val fragmentOutbound = outbounds.firstOrNull {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "levik-fragment"
        }
        assertTrue(fragmentOutbound != null)

        val selectedOutbound = outbounds.first().jsonObject
        val streamSettings = selectedOutbound["streamSettings"]?.jsonObject
        val sockopt = streamSettings?.get("sockopt")?.jsonObject
        assertEquals("levik-fragment", sockopt?.get("dialerProxy")?.jsonPrimitive?.content)

        val dns = config.getValue("dns").jsonObject
        val servers = dns.getValue("servers").jsonArray
        assertTrue(servers.any { it.jsonPrimitive.content == "https://1.1.1.1/dns-query" })
    }

    @Test
    fun `routes blocked only when BLOCKED_ONLY preset selected`() {
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val config = json.parseToJsonElement(
            builder.build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = 42,
                routingPreset = RoutingPreset.BLOCKED_ONLY,
            ),
        ).jsonObject

        val routing = config.getValue("routing").jsonObject
        val rules = routing.getValue("rules").jsonArray
        val proxyRule = rules.firstOrNull {
            it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "server-b"
        }
        assertTrue(proxyRule != null)
        assertTrue(proxyRule!!.jsonObject["domain"]?.jsonArray?.any {
            it.jsonPrimitive.content.contains("instagram.com")
        } == true)
    }

    @Test
    fun `builds kill switch lockdown config that blackholes all traffic`() {
        val config = json.parseToJsonElement(
            builder.buildKillSwitchConfig(7),
        ).jsonObject

        assertEquals("7", config.getValue("env").jsonObject
            .getValue("xray.tun.fd").jsonPrimitive.content)
        val inbound = config.getValue("inbounds").jsonArray.single().jsonObject
        assertEquals("tun", inbound.getValue("protocol").jsonPrimitive.content)
        val outbounds = config.getValue("outbounds").jsonArray
        assertEquals(1, outbounds.size)
        assertEquals("levik-block", outbounds[0].jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals("blackhole", outbounds[0].jsonObject.getValue("protocol").jsonPrimitive.content)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray
        val rule = rules.single().jsonObject
        assertEquals("tcp,udp", rule.getValue("network").jsonPrimitive.content)
        assertEquals("levik-block", rule.getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `rejects kill switch config with negative tun fd`() {
        try {
            builder.buildKillSwitchConfig(-1)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("TUN"))
        }
    }

    @Test
    fun `enforces UseIPv4 in DNS and freedom outbounds to prevent IPv6 leaks`() {
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
        )

        val config = json.parseToJsonElement(
            builder.build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = 42,
                antiDpiEnabled = true,
            ),
        ).jsonObject

        val dns = config.getValue("dns").jsonObject
        assertEquals("UseIPv4", dns.getValue("queryStrategy").jsonPrimitive.content)

        val outbounds = config.getValue("outbounds").jsonArray
        val directOutbound = outbounds.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "levik-direct"
        }.jsonObject
        assertEquals(
            "UseIPv4",
            directOutbound.getValue("settings").jsonObject.getValue("domainStrategy").jsonPrimitive.content,
        )

        val fragmentOutbound = outbounds.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "levik-fragment"
        }.jsonObject
        assertEquals(
            "UseIPv4",
            fragmentOutbound.getValue("settings").jsonObject.getValue("domainStrategy").jsonPrimitive.content,
        )

        val killSwitchConfig = json.parseToJsonElement(builder.buildKillSwitchConfig(42)).jsonObject
        val killSwitchDns = killSwitchConfig.getValue("dns").jsonObject
        assertEquals("UseIPv4", killSwitchDns.getValue("queryStrategy").jsonPrimitive.content)
    }

    private fun realityServer(id: String, tag: String, address: String = "de1.example.com"):
        TunnelServer = TunnelServer(
        id = id,
        tag = tag,
        name = tag,
        countryCode = "DE",
        outbound = buildJsonObject {
            put("protocol", "vless")
            put("tag", tag)
            put("settings", buildJsonObject {
                put("vnext", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("address", address)
                        put("port", 443)
                    })
                })
            })
            put("streamSettings", buildJsonObject {
                put("network", "tcp")
                put("security", "reality")
                put("realitySettings", buildJsonObject {
                    put("target", JsonNull)
                    put("dest", JsonNull)
                    put("publicKey", "pbk-value")
                    put("privateKey", "")
                    put("shortId", "abcd")
                    put("serverNames", kotlinx.serialization.json.buildJsonArray {})
                })
            })
        },
    )

    private fun server(id: String, tag: String): TunnelServer = TunnelServer(
        id = id,
        tag = tag,
        name = tag,
        countryCode = "DE",
        outbound = buildJsonObject {
            put("protocol", "vless")
            put("tag", tag)
            put("settings", buildJsonObject {})
        },
    )
}
