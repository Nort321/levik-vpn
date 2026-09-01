package com.leviknet.vpn.ui

import com.leviknet.vpn.core.network.FreeProxyResponse
import com.leviknet.vpn.data.AppRepository
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.TunnelEngineKind
import com.leviknet.vpn.vpn.TunnelNetworkRequirement
import com.leviknet.vpn.vpn.TunnelServerCategory
import com.leviknet.vpn.vpn.hasUnlimitedTraffic
import com.leviknet.vpn.vpn.isAllowlistMobileServer
import com.leviknet.vpn.vpn.isEligibleForAutomaticSelection
import com.leviknet.vpn.vpn.isRussianServer
import com.leviknet.vpn.vpn.isStandardMobileServer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FreeProxyAndServerCategoryTest {

    @Test
    fun `parses free proxy response from web api successfully`() {
        val json = """
            {
                "ok": true,
                "link": "tg://proxy?server=mt.leviknet.com&port=31443&secret=1cb61164c70fc4d193569b05f34e3f7d",
                "deviceLimit": 1,
                "rateLimitMbps": 15
            }
        """.trimIndent()
        val parsed = Json.decodeFromString<FreeProxyResponse>(json)
        assertTrue(parsed.ok)
        assertEquals("tg://proxy?server=mt.leviknet.com&port=31443&secret=1cb61164c70fc4d193569b05f34e3f7d", parsed.link)
        assertEquals(1, parsed.deviceLimit)
        assertEquals(15, parsed.rateLimitMbps)
    }

    @Test
    fun `default proxy link has valid tg scheme and proxy host`() {
        assertTrue(AppRepository.DEFAULT_FREE_PROXY_TG_LINK.startsWith("tg://proxy?"))
    }

    @Test
    fun `server filter types include all regular and mobile`() {
        val values = ServerFilterType.values()
        assertTrue(values.contains(ServerFilterType.ALL))
        assertTrue(values.contains(ServerFilterType.REGULAR))
        assertTrue(values.contains(ServerFilterType.MOBILE))
        assertTrue(values.contains(ServerFilterType.MOBILE_ALLOWLIST))
        assertTrue(values.contains(ServerFilterType.FAVORITES))
        assertTrue(values.contains(ServerFilterType.FASTEST))
    }

    @Test
    fun `identifies mobile and regular servers correctly`() {
        fun isMobile(server: TunnelServer): Boolean {
            val n = server.name.uppercase(Locale.ROOT)
            val t = server.tag.uppercase(Locale.ROOT)
            return n.contains("LTE") || n.contains("MOBILE") || n.contains("МОБИЛЬН") ||
                t.contains("LTE") || t.contains("MOBILE")
        }

        val lteServer1 = TunnelServer(
            id = "1",
            tag = "levik-lte-1",
            name = "LTE ⚡",
            countryCode = "RU",
            outbound = JsonObject(emptyMap()),
        )
        val lteServer2 = TunnelServer(
            id = "2",
            tag = "levik-2",
            name = "LTE • Universal",
            countryCode = "RU",
            outbound = JsonObject(emptyMap()),
        )
        val regularServer = TunnelServer(
            id = "3",
            tag = "levik-de",
            name = "Germany Prime",
            countryCode = "DE",
            outbound = JsonObject(emptyMap()),
        )

        assertTrue(isMobile(lteServer1))
        assertTrue(isMobile(lteServer2))
        assertFalse(isMobile(regularServer))
    }

    @Test
    fun `russian servers are manual-only`() {
        val russian = TunnelServer(
            id = "ru",
            tag = "russia-fallback",
            name = "Россия",
            countryCode = "ru",
            outbound = JsonObject(emptyMap()),
        )
        val german = TunnelServer(
            id = "de",
            tag = "germany",
            name = "Germany",
            countryCode = "DE",
            outbound = JsonObject(emptyMap()),
        )

        assertTrue(russian.isRussianServer())
        assertFalse(russian.isEligibleForAutomaticSelection())
        assertTrue(german.isEligibleForAutomaticSelection())
        assertTrue(german.copy(name = "LTE Germany").isEligibleForAutomaticSelection())
    }

    @Test
    fun `allow-list relay is an explicit third category and never automatic`() {
        val relay = TunnelServer(
            id = "relay:de-1",
            tag = "relay:de-1",
            name = "Mobile Allow-list",
            countryCode = "DE",
            outbound = JsonObject(emptyMap()),
            engine = TunnelEngineKind.LEVIK_RELAY,
            category = TunnelServerCategory.MOBILE_ALLOWLIST,
            networkRequirement = TunnelNetworkRequirement.CELLULAR_ALLOWLIST,
        )

        assertTrue(relay.isAllowlistMobileServer())
        assertFalse(relay.isEligibleForAutomaticSelection())
    }

    @Test
    fun `whitelist relay and regular servers have unlimited traffic while standard mobile does not`() {
        val regular = TunnelServer(
            id = "reg-1",
            tag = "reg-1",
            name = "Regular Server",
            countryCode = "FI",
            outbound = JsonObject(emptyMap()),
            category = TunnelServerCategory.REGULAR,
        )
        val lte = TunnelServer(
            id = "lte-1",
            tag = "lte-1",
            name = "Mobile Server",
            countryCode = "RU",
            outbound = JsonObject(emptyMap()),
            category = TunnelServerCategory.MOBILE,
        )
        val relay = TunnelServer(
            id = "relay:ru-1",
            tag = "relay:ru-1",
            name = "Allowlist Server",
            countryCode = "RU",
            outbound = JsonObject(emptyMap()),
            engine = TunnelEngineKind.LEVIK_RELAY,
            category = TunnelServerCategory.MOBILE_ALLOWLIST,
        )

        assertTrue(regular.hasUnlimitedTraffic())
        assertFalse(regular.isStandardMobileServer())

        assertFalse(lte.hasUnlimitedTraffic())
        assertTrue(lte.isStandardMobileServer())

        assertTrue(relay.hasUnlimitedTraffic())
        assertFalse(relay.isStandardMobileServer())
    }
}
