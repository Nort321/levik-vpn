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
    fun `server filters expose a single allow-list category`() {
        val values = ServerFilterType.values()
        assertTrue(values.contains(ServerFilterType.ALL))
        assertTrue(values.contains(ServerFilterType.REGULAR))
        assertFalse(values.any { it.name == "MOBILE" })
        assertTrue(values.contains(ServerFilterType.MOBILE_ALLOWLIST))
        assertTrue(values.contains(ServerFilterType.FAVORITES))
        assertTrue(values.contains(ServerFilterType.FASTEST))
    }

    @Test
    fun `allow-list filter includes every LTE and relay server including cached profiles`() {
        val regular = TunnelServer(
            id = "regular", tag = "germany", name = "Germany", countryCode = "DE",
            outbound = JsonObject(emptyMap()),
        )
        val lteByName = regular.copy(id = "lte-name", name = "LTE Universal")
        val lteByTag = regular.copy(id = "lte-tag", tag = "levik-lte-2")
        val cachedMobile = regular.copy(id = "cached", category = TunnelServerCategory.MOBILE)
        val relay = regular.copy(
            id = "relay:1", engine = TunnelEngineKind.LEVIK_RELAY,
            category = TunnelServerCategory.MOBILE_ALLOWLIST,
        )
        val servers = listOf(regular, lteByName, lteByTag, cachedMobile, relay)

        assertEquals(
            listOf(lteByName, lteByTag, cachedMobile, relay),
            servers.filter { ServerFilterType.MOBILE_ALLOWLIST.matches(it, emptySet()) },
        )
        assertEquals(listOf(regular), servers.filter { ServerFilterType.REGULAR.matches(it, emptySet()) })
        assertEquals(listOf(lteByTag), servers.filter { ServerFilterType.FAVORITES.matches(it, setOf(lteByTag.id)) })
        assertEquals(servers, servers.filter { ServerFilterType.ALL.matches(it, emptySet()) })
    }

    @Test
    fun `regular russian servers are manual-only`() {
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
    fun `mobile-only subscription retains an automatic candidate alongside relay`() {
        val lte = TunnelServer(
            id = "lte-ru",
            tag = "lte-ru",
            name = "LTE",
            countryCode = "RU",
            outbound = JsonObject(emptyMap()),
            category = TunnelServerCategory.MOBILE,
        )
        val relay = lte.copy(
            id = "relay:de-1",
            tag = "relay:de-1",
            countryCode = "DE",
            engine = TunnelEngineKind.LEVIK_RELAY,
            category = TunnelServerCategory.MOBILE_ALLOWLIST,
        )

        assertEquals(listOf(lte), listOf(lte).filter(TunnelServer::isEligibleForAutomaticSelection))
        assertEquals(listOf(lte), listOf(lte, relay).filter(TunnelServer::isEligibleForAutomaticSelection))
        assertFalse(lte.copy(category = TunnelServerCategory.REGULAR).isEligibleForAutomaticSelection())
    }

    @Test
    fun `legacy russian LTE profile remains eligible after updating the app`() {
        val cached = TunnelServer(
            id = "cached-lte",
            tag = "levik-0",
            name = "LTE",
            countryCode = "ru",
            outbound = JsonObject(emptyMap()),
        )

        assertTrue(cached.isEligibleForAutomaticSelection())
        assertTrue(cached.copy(name = "Мобильный VPN").isEligibleForAutomaticSelection())
        assertFalse(cached.copy(name = "Россия").isEligibleForAutomaticSelection())
        assertFalse(cached.copy(engine = TunnelEngineKind.LEVIK_RELAY).isEligibleForAutomaticSelection())
    }

    @Test
    fun `allow-list relay retains its runtime category and is never automatic`() {
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
