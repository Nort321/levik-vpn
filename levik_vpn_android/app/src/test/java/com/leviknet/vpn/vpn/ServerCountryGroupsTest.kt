package com.leviknet.vpn.vpn

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerCountryGroupsTest {
    @Test
    fun `reads the server label from the host remark`() {
        assertEquals("Франкфурт", serverLabel("🇩🇪 Германия · Франкфурт", "DE"))
        assertEquals("Нюрнберг 2", serverLabel("🇩🇪 Германия | Нюрнберг 2", "DE"))
        assertEquals("Frankfurt", serverLabel("🇩🇪 Germany Frankfurt", "DE"))
        assertEquals("Prime", serverLabel("🇩🇪 🚀 Prime", "DE"))
        assertNull(serverLabel("🇩🇪 Германия", "DE"))
        assertNull(serverLabel("🇩🇪 🛡️ Hysteria2", "DE"))
        assertNull(serverLabel("🇩🇪 TUIC", "DE"))
        assertNull(serverLabel("🇩🇪 Германия VLESS Reality", "DE"))
    }

    @Test
    fun `puts every server of a country under one card`() {
        val countries = groupByCountry(
            listOf(
                server("de1-vless", "🇩🇪 Германия · Франкфурт", "de1.example.net"),
                server("fi1", "🇫🇮 Финляндия", "fi1.example.net", countryCode = "FI"),
                server("de1-hy2", "🇩🇪 🛡️ Hysteria2", "de1.example.net", protocol = "hysteria2"),
                server("de2", "🇩🇪 Германия · Нюрнберг", "de2.example.net"),
            ),
        )
        assertEquals(listOf("DE", "FI"), countries.map { it.countryCode })
        assertEquals(
            listOf("Франкфурт" to listOf("de1-vless", "de1-hy2"), "Нюрнберг" to listOf("de2")),
            countries[0].nodes.map { node -> node.label to node.group.variants.map(TunnelServer::id) },
        )
        assertNull(countries[1].nodes.single().label)
    }

    @Test
    fun `numbers servers without a label and repeated labels`() {
        val nodes = groupByCountry(
            listOf(
                server("a", "🇩🇪 Германия", "a.example.net"),
                server("b", "🇩🇪 Германия", "b.example.net"),
                server("c", "🇩🇪 Германия · Франкфурт", "c.example.net"),
                server("d", "🇩🇪 Германия · Франкфурт", "d.example.net"),
            ),
        ).single().nodes
        assertEquals(listOf(null, null, "Франкфурт", "Франкфурт 2"), nodes.map(CountryServerNode::label))
        assertEquals(listOf(1, 2, 3, 4), nodes.map(CountryServerNode::number))
    }

    @Test
    fun `keeps mobile, home exits, unknown countries and other protocols on cards of their own`() {
        val countries = groupByCountry(
            listOf(
                server("lte", "🇩🇪 LTE", "lte.example.net", category = TunnelServerCategory.MOBILE),
                server("home", "🏠 Дом", "home.example.net"),
                server("trojan", "🇩🇪 Trojan", "t.example.net", protocol = "trojan"),
                server("unknown", "Сервер", "u.example.net", countryCode = "XX"),
                server("de", "🇩🇪 Германия", "de.example.net"),
            ),
        )
        assertEquals(listOf(null, null, null, null, "DE"), countries.map(CountryServerGroup::countryCode))
        assertEquals(listOf("🇩🇪 LTE", "🏠 Дом", "🇩🇪 Trojan", "Сервер", null), countries.map { it.nodes.single().label })
    }

    @Test
    fun `lists every variant of a country`() {
        val servers = listOf(
            server("de1", "🇩🇪 Германия · Франкфурт", "de1.example.net"),
            server("fi1", "🇫🇮 Финляндия", "fi1.example.net", countryCode = "FI"),
            server("de2", "🇩🇪 Германия · Нюрнберг", "de2.example.net"),
        )
        assertEquals(listOf("de1", "de2"), countryServers(servers, "de").map(TunnelServer::id))
        assertEquals(emptyList<TunnelServer>(), countryServers(servers, "NL"))
    }

    private fun server(
        id: String,
        name: String,
        address: String,
        protocol: String = "vless",
        countryCode: String = "DE",
        category: TunnelServerCategory = TunnelServerCategory.REGULAR,
    ) = TunnelServer(
        id = id,
        tag = id,
        name = name,
        countryCode = countryCode,
        category = category,
        outbound = buildJsonObject {
            put("protocol", protocol)
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", address)
                        put("port", 443)
                    })
                })
            })
        },
    )
}
