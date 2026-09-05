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
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray

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
        val ipv6LeakProtection = rules.first().jsonObject
        assertEquals("levik-block", ipv6LeakProtection
            .getValue("outboundTag").jsonPrimitive.content)
        assertEquals("::/0", ipv6LeakProtection
            .getValue("ip").jsonArray.single().jsonPrimitive.content)
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
        assertTrue(rules[1].jsonObject.getValue("domain").jsonArray.any {
            it.jsonPrimitive.content == "domain:ru"
        })
        assertTrue(rules[2].jsonObject.getValue("ip").jsonArray.any {
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
        assertTrue(rules.drop(1).single().jsonObject.getValue("ip").jsonArray.none {
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
        assertEquals("REALITY server settings are incomplete", error?.message)
        assertFalse(error?.message?.contains(selected.tag) == true)
        assertFalse(error?.message?.contains("203.0.113.5") == true)
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

        val fragmentSettings = fragmentOutbound!!.jsonObject["settings"]?.jsonObject?.get("fragment")?.jsonObject
        assertEquals("tlshello", fragmentSettings?.get("packets")?.jsonPrimitive?.content)
        assertEquals("100-200", fragmentSettings?.get("length")?.jsonPrimitive?.content)
        assertEquals("10-20", fragmentSettings?.get("interval")?.jsonPrimitive?.content)

        val selectedOutbound = outbounds.first().jsonObject
        val streamSettings = selectedOutbound["streamSettings"]?.jsonObject
        val sockopt = streamSettings?.get("sockopt")?.jsonObject
        assertEquals("levik-fragment", sockopt?.get("dialerProxy")?.jsonPrimitive?.content)

        val dns = config.getValue("dns").jsonObject
        val servers = dns.getValue("servers").jsonArray
        assertTrue(servers.any { it.jsonPrimitive.content == "https://1.1.1.1/dns-query" })
    }

    @Test
    fun `injects custom and micro fragment params when specified`() {
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
                antiDpiPackets = "1-5",
                antiDpiLength = "1-5",
                antiDpiInterval = "5-15",
            ),
        ).jsonObject

        val outbounds = config.getValue("outbounds").jsonArray
        val fragmentOutbound = outbounds.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "levik-fragment"
        }.jsonObject
        val fragment = fragmentOutbound.getValue("settings").jsonObject.getValue("fragment").jsonObject

        assertEquals("1-5", fragment.getValue("packets").jsonPrimitive.content)
        assertEquals("1-5", fragment.getValue("length").jsonPrimitive.content)
        assertEquals("5-15", fragment.getValue("interval").jsonPrimitive.content)
    }

    @Test
    fun `sanitizes invalid Anti-DPI parameters to safe defaults`() {
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
                antiDpiPackets = "evil;injection{\"",
                antiDpiLength = "invalid length spaces",
                antiDpiInterval = "   ",
            ),
        ).jsonObject

        val outbounds = config.getValue("outbounds").jsonArray
        val fragmentOutbound = outbounds.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "levik-fragment"
        }.jsonObject
        val fragment = fragmentOutbound.getValue("settings").jsonObject.getValue("fragment").jsonObject

        assertEquals("tlshello", fragment.getValue("packets").jsonPrimitive.content)
        assertEquals("100-200", fragment.getValue("length").jsonPrimitive.content)
        assertEquals("10-20", fragment.getValue("interval").jsonPrimitive.content)
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

    @Test
    fun `LTE profile bypasses only its pinned domain and CIDR rules`() {
        val selected = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(selected),
            directCidrs = listOf("192.0.2.0/24"),
            directDomains = listOf("domain:user-direct.example"),
            proxyDomains = listOf("domain:user-proxy.example"),
        )

        val config = json.parseToJsonElement(
            builder.build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = 42,
                routingPreset = RoutingPreset.BYPASS_RU,
                bypassRussianTraffic = true,
                russianDirectCidrs = listOf("198.51.100.0/24"),
                customDirectDomains = setOf("custom-direct.example"),
                customProxyDomains = setOf("custom-proxy.example"),
                effectiveRoutingProfile = EffectiveRoutingProfile.LTE,
                lteDirectCidrs = listOf("203.0.113.0/24"),
                lteDirectDomains = listOf("domain:allowed.example"),
            ),
        ).jsonObject
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray
        val serializedRules = rules.toString()

        assertTrue(serializedRules.contains("domain:allowed.example"))
        assertTrue(serializedRules.contains("203.0.113.0/24"))
        assertFalse(serializedRules.contains("user-direct.example"))
        assertFalse(serializedRules.contains("user-proxy.example"))
        assertFalse(serializedRules.contains("custom-direct.example"))
        assertFalse(serializedRules.contains("custom-proxy.example"))
        assertFalse(serializedRules.contains("198.51.100.0/24"))
        assertFalse(serializedRules.contains("10.0.0.0/8"))
        assertFalse(serializedRules.contains("192.168.0.0/16"))
        assertTrue(rules.none { rule ->
            rule.jsonObject["network"]?.jsonPrimitive?.content == "tcp,udp"
        })
    }

    @Test
    fun `relay profile uses authenticated loopback proxy with LTE routing`() {
        val source = server("b".repeat(64), "server-b")
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "profile",
            subscriptionId = "subscription",
            issuedAt = "2026-07-29T11:59:00Z",
            subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            servers = listOf(source),
        )

        val config = json.parseToJsonElement(
            builder.buildRelayProxy(
                profile = profile,
                tunFileDescriptor = 42,
                proxy = LocalProxyEndpoint(
                    address = "127.0.0.1",
                    port = 32123,
                    username = "u".repeat(24),
                    password = "p".repeat(48),
                ),
                primaryDnsIp = "1.1.1.1",
                secondaryDnsIp = "1.0.0.1",
                lteDirectCidrs = listOf("203.0.113.0/24"),
                lteDirectDomains = listOf("domain:allowed.example"),
            ),
        ).jsonObject
        val proxy = config.getValue("outbounds").jsonArray.first().jsonObject
        val proxyServer = proxy.getValue("settings").jsonObject

        assertEquals("socks", proxy.getValue("protocol").jsonPrimitive.content)
        assertEquals("127.0.0.1", proxyServer.getValue("address").jsonPrimitive.content)
        assertEquals("32123", proxyServer.getValue("port").jsonPrimitive.content)
        assertTrue(config.getValue("routing").toString().contains("domain:allowed.example"))
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
