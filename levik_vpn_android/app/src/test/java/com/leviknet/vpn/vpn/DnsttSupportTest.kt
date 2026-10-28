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

    @Test fun `DNS uses TCP through proxy before any direct routing rule`() {
        val base = """{"dns":{"servers":["1.1.1.1"]},"inbounds":[{"tag":"tun","sniffing":{"enabled":true}}],"outbounds":[{"tag":"proxy","protocol":"socks"},{"tag":"levik-block","protocol":"blackhole"}],"routing":{"rules":[{"type":"field","ip":["geoip:ru"],"outboundTag":"direct"}]}}"""
        val config = Json.parseToJsonElement(dnsttTcpConfig(base)).jsonObject
        val resolvers = config.getValue("dns").jsonObject.getValue("servers").jsonArray
        assertEquals(listOf("tcp://77.88.8.8", "tcp://77.88.8.1"), resolvers.map { it.jsonObject.getValue("address").jsonPrimitive.content })
        assertTrue(resolvers.all { it.jsonObject.getValue("timeoutMs").jsonPrimitive.content.toInt() >= 20_000 })
        assertEquals("AsIs", config.getValue("routing").jsonObject.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals("true", config.getValue("inbounds").jsonArray.first().jsonObject.getValue("sniffing").jsonObject.getValue("routeOnly").jsonPrimitive.content)
        val dnsOutbound = config.getValue("outbounds").jsonArray.last().jsonObject
        assertEquals("proxy", dnsOutbound.getValue("proxySettings").jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals("tcp", dnsOutbound.getValue("settings").jsonObject.getValue("rewriteNetwork").jsonPrimitive.content)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray
        assertEquals("proxy", rules[0].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("dnstt-dns", rules[1].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("levik-block", rules.last().jsonObject.getValue("outboundTag").jsonPrimitive.content)
    }
}
