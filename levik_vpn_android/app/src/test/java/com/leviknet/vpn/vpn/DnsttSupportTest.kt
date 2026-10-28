package com.leviknet.vpn.vpn

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DnsttSupportTest {
    private val link = "dnstt://${"a".repeat(32)}:${"p".repeat(32)}@t.levikfartik.ru?pubkey=${"ab".repeat(32)}&resolver=77.88.8.8&qps=40#DNS"

    @Test fun `DNS has its own category and is excluded from automatic selection`() {
        val server = DnsttLinkParser.parse(Base64.getEncoder().encodeToString(link.toByteArray())).single()
        assertEquals(TunnelEngineKind.LEVIK_DNSTT, server.engine)
        assertEquals(TunnelServerCategory.DNS_TEST, server.effectiveCategory())
        assertEquals("77.88.8.8", server.dnsttConfig?.resolver)
        assertFalse(server.isEligibleForAutomaticSelection())
        assertFalse(server.isMobileServer())
    }

    @Test fun `rejects arbitrary resolvers malformed keys and unbounded request rates`() {
        for (invalid in listOf(
            link.replace("77.88.8.8", "1.1.1.1"), link.replace("qps=40", "qps=1000"),
            link.replace("qps=40", "qps=0"), link.replace("ab".repeat(32), "0".repeat(64)),
            link.replace("#DNS", "&resolver=77.88.8.1#DNS"), link.replace("t.levikfartik.ru", "t.levikfartik.ru:53"),
            link.replace("p".repeat(32), "short"), link.replace("#DNS", "&unexpected=yes#DNS"),
        )) assertNull(invalid, DnsttLinkParser.parseLine(invalid))
    }

    @Test fun `app-only links never reach the native Xray converter`() {
        val regular = "vless://user@example.org:443"
        val source = TunnelProfileSource("text/plain", Base64.getEncoder().encodeToString("$regular\n$link".toByteArray()))
        val cleaned = requireNotNull(DnsttLinkParser.withoutLinks(source))
        assertEquals(regular, Base64.getDecoder().decode(cleaned.content).decodeToString())
        assertNull(DnsttLinkParser.withoutLinks(TunnelProfileSource("text/plain", link)))
    }

    @Test fun `application lookups get fake addresses and names reach the node`() {
        val base = """{"dns":{"servers":["1.1.1.1"]},"inbounds":[{"tag":"tun","sniffing":{"enabled":true,"destOverride":["http","tls"]}}],"outbounds":[{"tag":"proxy","protocol":"socks"},{"tag":"levik-direct","protocol":"freedom"},{"tag":"levik-block","protocol":"blackhole"}],"routing":{"rules":[{"type":"field","ip":["geoip:ru"],"outboundTag":"levik-direct"}]}}"""
        val config = Json.parseToJsonElement(dnsttTcpConfig(base)).jsonObject
        val resolvers = config.getValue("dns").jsonObject.getValue("servers").jsonArray
        assertEquals("fakedns", resolvers.first().jsonPrimitive.content)
        assertEquals(listOf("77.88.8.8", "77.88.8.1"), resolvers.drop(1).map { it.jsonObject.getValue("address").jsonPrimitive.content })
        val pool = config.getValue("fakedns").jsonArray.single().jsonObject.getValue("ipPool").jsonPrimitive.content
        assertEquals(DNSTT_FAKE_IP_POOL, pool)
        assertEquals("AsIs", config.getValue("routing").jsonObject.getValue("domainStrategy").jsonPrimitive.content)
        val sniffing = config.getValue("inbounds").jsonArray.first().jsonObject.getValue("sniffing").jsonObject
        assertEquals("false", sniffing.getValue("routeOnly").jsonPrimitive.content)
        assertEquals(listOf("fakedns", "http", "tls"), sniffing.getValue("destOverride").jsonArray.map { it.jsonPrimitive.content })
        val dnsOutbound = config.getValue("outbounds").jsonArray.last().jsonObject
        assertEquals("proxy", dnsOutbound.getValue("proxySettings").jsonObject.getValue("tag").jsonPrimitive.content)
        val dnsRules = dnsOutbound.getValue("settings").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("hijack", dnsRules.first().getValue("action").jsonPrimitive.content)
        assertEquals("1", dnsRules.first().getValue("qType").jsonPrimitive.content)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray
        // Xray's own lookups serve direct-routed names only; they never enter the tunnel.
        assertEquals("levik-direct", rules[0].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("dnstt-dns", rules[1].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("levik-block", rules.last().jsonObject.getValue("outboundTag").jsonPrimitive.content)
    }

    @Test fun `fake address pool is routed into the tunnel`() {
        val plan = xrayTunPlan("77.88.8.8", "77.88.8.1").copy(extraIpv4Routes = listOf(DNSTT_FAKE_IP_POOL))
        assertTrue(VpnRoutes.nativeExcludedIpv4Networks.contains("198.18.0.0/15"))
        assertEquals(listOf(DNSTT_FAKE_IP_POOL), plan.extraIpv4Routes)
        assertThrows(IllegalArgumentException::class.java) { plan.copy(extraIpv4Routes = listOf("::/0")) }
    }
}
