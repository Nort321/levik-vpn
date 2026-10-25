package com.leviknet.vpn.vpn

import com.leviknet.vpn.data.RoutingPreset
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRoutesTest {
    private val main = server("main", "192.0.2.1")
    private val de = server("de", "192.0.2.2")
    private val de2 = server("de2", "192.0.2.3")
    private val nl = server("nl", "192.0.2.4")
    private val ordinaryXhttp = server("xhttp", "192.0.2.5", "xhttp")
    private val lteOnSameHost = server("lte", "192.0.2.5", "xhttp", 9443)
    private val rule = ServiceRouteRule("country-rule", listOf("regexp:^.+\\.ru$"), ServiceRouteTarget("country", "DE"), ServiceRouteTarget("main"), "domain", "*.ru")
    private val exits = listOf(exit("main", "US", main), exit("de", "DE", de), exit("de2", "DE", de2), exit("nl", "NL", nl), exit("xhttp", "FR", ordinaryXhttp))
    private val policy = ServiceRouteProfile(1, 7, "a".repeat(64), listOf(rule), exits)
    private val profile = PreparedTunnelProfile(1, "test", "subscription", "2026-10-25T00:00:00Z", servers = listOf(main, de, de2, nl, ordinaryXhttp, lteOnSameHost), serviceRoutes = policy)

    @Test
    fun `endpoint role is authoritative and matches port and protocol rather than name or xhttp`() {
        policy.validate()
        assertEquals("xhttp", policy.exitFor(ordinaryXhttp)?.exitId)
        assertNull(policy.exitFor(lteOnSameHost))
        assertNull(policy.exitFor(main.copy(engine = TunnelEngineKind.LEVIK_RELAY)))
        assertNull(policy.exitFor(main.copy(engine = TunnelEngineKind.LEVIK_GAME)))
        val ambiguous = policy.copy(exits = exits + exit("duplicate", "US", main))
        assertNull(ambiguous.exitFor(main))
    }

    @Test
    fun `country selection stays sticky then uses same country then explicit main fallback`() {
        val selector = ServiceRouteSelection(policy, "main")
        assertEquals("de2", selector.select(rule, setOf("de2", "main")).exitId)
        assertEquals("de2", selector.select(rule, setOf("de", "de2", "main")).exitId)
        assertEquals("de", selector.select(rule, setOf("de", "main")).exitId)
        assertEquals(ServiceRouteSelection.Decision("main", "fallback", "main"), selector.select(rule, setOf("main", "nl")))
        assertEquals(ServiceRouteSelection.BLOCK, selector.select(rule, setOf("nl")).target)
    }

    @Test
    fun `pin never substitutes another exit and direct requires explicit fallback`() {
        val selector = ServiceRouteSelection(policy, "main")
        val pinned = rule.copy(route = ServiceRouteTarget("exit", exitId = "de"), fallback = ServiceRouteTarget("block"))
        assertEquals("blocked", selector.select(pinned, setOf("de2", "main")).state)
        assertEquals("de", selector.select(pinned, setOf("de", "de2", "main")).exitId)
        assertEquals(ServiceRouteSelection.Decision(ServiceRouteSelection.DIRECT, "fallback", null), selector.select(pinned.copy(fallback = ServiceRouteTarget("direct")), emptySet()))
    }

    @Test
    fun `main outage leaves an independent country route available`() {
        val selector = ServiceRouteSelection(policy, "main")
        val mainRule = rule.copy(id = "main-rule", route = ServiceRouteTarget("main"), fallback = ServiceRouteTarget("block"))
        assertEquals("blocked", selector.select(mainRule, setOf("de")).state)
        assertEquals("de", selector.select(rule, setOf("de")).exitId)
    }

    @Test
    fun `health requires confirmed failures and stable recovery`() {
        val health = ServiceExitHealth()
        assertFalse(health.alive)
        health.observe(true)
        assertTrue(health.alive)
        val unavailableAtStartup = ServiceExitHealth()
        repeat(3) { unavailableAtStartup.observe(false) }
        unavailableAtStartup.observe(true)
        assertFalse(unavailableAtStartup.alive)
        unavailableAtStartup.observe(true)
        assertTrue(unavailableAtStartup.alive)
        repeat(2) { health.observe(false) }
        assertTrue(health.alive)
        health.observe(false)
        assertFalse(health.alive)
        health.observe(true)
        assertFalse(health.alive)
        health.observe(true)
        assertTrue(health.alive)
    }

    @Test
    fun `last good cache cannot cross authenticated session or subscription boundaries`() {
        val cached = com.leviknet.vpn.data.AppRepository.CachedServiceRoutes("subscription-a", "a".repeat(64), policy)
        assertTrue(cached.belongsTo("subscription-a", "a".repeat(64)))
        assertFalse(cached.belongsTo("subscription-b", "a".repeat(64)))
        assertFalse(cached.belongsTo("subscription-a", "b".repeat(64)))
        assertFalse(cached.belongsTo("subscription-b", "b".repeat(64)))
        val other = com.leviknet.vpn.data.AppRepository.CachedServiceRoutes("subscription-b", "a".repeat(64), policy)
        val cache = com.leviknet.vpn.data.AppRepository.CachedServiceRouteSet().updated(cached).updated(other)
        assertEquals(2, cache.records.size)
        assertEquals(cached, cache.records.single { it.belongsTo("subscription-a", "a".repeat(64)) })
        val full = (1..100).fold(com.leviknet.vpn.data.AppRepository.CachedServiceRouteSet()) { set, index ->
            set.updated(cached.copy(subscriptionId = "subscription-$index"))
        }
        assertEquals(100, full.updated(cached.copy(subscriptionId = "subscription-1")).records.size)
        assertTrue(runCatching { full.updated(cached) }.exceptionOrNull() is com.leviknet.vpn.data.AppRepository.ServiceRouteCacheLimitException)
        assertTrue(full.records.any { it.subscriptionId == "subscription-1" })
    }

    @Test
    fun `default and direct stay explicit and unavailable country has a finite terminal`() {
        val selector = ServiceRouteSelection(policy, "main")
        assertEquals(ServiceRouteSelection.BASE, selector.select(rule.copy(route = ServiceRouteTarget("default")), emptySet()).target)
        assertEquals(ServiceRouteSelection.DIRECT, selector.select(rule.copy(route = ServiceRouteTarget("direct")), emptySet()).target)
        val secondary = rule.copy(fallback = ServiceRouteTarget("country", "NL"))
        assertEquals("nl", selector.select(secondary, setOf("nl")).exitId)
        assertEquals("blocked", selector.select(secondary, setOf("main")).state)
    }

    @Test
    fun `compiled policy uses private control sockets fixed probes same DNS balancer and no observer fallback`() {
        val session = requireNotNull(ServiceRouteConfig.prepare(profile, main, File("/private/tmp")))
        val json = Json.parseToJsonElement(XrayConfigBuilder(Json).build(profile, main.id, 42, serviceRouteSession = session)).jsonObject
        val inbounds = json.getValue("inbounds").jsonArray.map { it.jsonObject }
        val control = inbounds.first { it["tag"]?.jsonPrimitive?.content == "levik-sr-api-in" }
        assertTrue(control.getValue("listen").jsonPrimitive.content.endsWith(",0600"))
        assertFalse(control.containsKey("port"))
        assertFalse(session.candidates.any { it.outboundTag == lteOnSameHost.tag })
        val routing = json.getValue("routing").jsonObject
        assertTrue(routing.getValue("balancers").jsonArray.none { it.jsonObject.containsKey("fallbackTag") })
        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(rules.any { it["domain"] == Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), rule.domains) && it["balancerTag"]?.jsonPrimitive?.content == ServiceRouteConfig.routeTag(0) })
        val dns = json.getValue("dns").jsonObject
        val routedResolver = dns.getValue("servers").jsonArray.first().jsonObject
        assertEquals("levik-sr-dns-0", routedResolver.getValue("tag").jsonPrimitive.content)
        assertEquals("true", dns.getValue("disableFallbackIfMatch").jsonPrimitive.content)
        assertEquals("true", dns.getValue("disableCache").jsonPrimitive.content)
        System.getenv("LEVIK_SERVICE_ROUTES_CONFIG_FIXTURE")?.let { target ->
            // Optional synthetic fixture for validating this exact config on the pinned host binary.
            val withoutTun = JsonObject(json - "env" + ("inbounds" to kotlinx.serialization.json.JsonArray(
                inbounds.filter { it["protocol"]?.jsonPrimitive?.content != "tun" },
            )))
            File(target).writeText(withoutTun.toString())
        }
    }

    @Test
    fun `unknown endpoint and LTE routing keep their original configuration unchanged`() {
        val builder = XrayConfigBuilder(Json)
        val baseline = profile.copy(serviceRoutes = null)
        assertEquals(builder.build(baseline, lteOnSameHost.id, 42), builder.build(profile, lteOnSameHost.id, 42))
        fun lteConfig(input: PreparedTunnelProfile) = builder.build(input, main.id, 42,
            routingPreset = RoutingPreset.BYPASS_RU, effectiveRoutingProfile = EffectiveRoutingProfile.LTE,
            lteDirectDomains = listOf("domain:existing.test"), lteDirectCidrs = listOf("192.0.2.0/24"))
        assertEquals(lteConfig(baseline), lteConfig(profile))
        assertNull(ServiceRouteConfig.prepare(profile.copy(version = 2), lteOnSameHost, File("/private/tmp")))
        assertNotNull(ServiceRouteConfig.prepare(profile, main, File("/private/tmp")))
    }

    @Test
    fun `ordinary selection works in mixed relay and Yandex prepared containers`() {
        for (version in listOf(2, 3, 4)) {
            assertNotNull(ServiceRouteConfig.prepare(profile.copy(version = version), main, File("/private/tmp")))
            assertNull(ServiceRouteConfig.prepare(profile.copy(version = version), lteOnSameHost, File("/private/tmp")))
        }
    }

    @Test
    fun `default plain DNS reentry bypasses interception and follows base routing`() {
        val plainPolicy = policy.copy(rules = listOf(rule.copy(domains = listOf("domain:plain.example.test"), route = ServiceRouteTarget("default"))))
        val prepared = profile.copy(serviceRoutes = plainPolicy)
        val session = requireNotNull(ServiceRouteConfig.prepare(prepared, main, File("/private/tmp")))
        val config = Json.parseToJsonElement(XrayConfigBuilder(Json).build(prepared, main.id, 42, serviceRouteSession = session)).jsonObject
        val interception = config.getValue("routing").jsonObject.getValue("rules").jsonArray.first { it.jsonObject["port"]?.jsonPrimitive?.content == "53" }.jsonObject
        assertEquals(listOf("levik-tun-in"), interception.getValue("inboundTag").jsonArray.map { it.jsonPrimitive.content })
        System.getenv("LEVIK_SERVICE_ROUTES_CONFIG_FIXTURE")?.let { target ->
            File("$target.plain.json").writeText(JsonObject(config - "env" + ("inbounds" to kotlinx.serialization.json.JsonArray(
                config.getValue("inbounds").jsonArray.filter { it.jsonObject["protocol"]?.jsonPrimitive?.content != "tun" },
            ))).toString())
        }
    }

    @Test
    fun `pinned API protobuf framing contains exact field numbers and bounded UTF8 lengths`() {
        val request = ServiceRouteGrpc.overrideRequest("abc", "de")
        assertTrue(request.contentEquals(byteArrayOf(0, 0, 0, 0, 9, 10, 3, 97, 98, 99, 18, 2, 100, 101)))
    }

    private fun server(id: String, host: String, network: String = "tcp", port: Int = 443): TunnelServer = TunnelServer(
        id, "test-$id", id, "XX", buildJsonObject {
            put("tag", "test-$id"); put("protocol", "vless")
            put("settings", buildJsonObject { put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", host); put("port", port)
                    put("users", buildJsonArray { add(buildJsonObject { put("id", "11111111-1111-4111-8111-111111111111"); put("encryption", "none") }) })
                })
            }) })
            put("streamSettings", buildJsonObject { put("network", network) })
        },
    )
    private fun exit(id: String, country: String, server: TunnelServer) = ServiceRouteExit(id, id, country, "enabled", "ordinary", listOf(ServiceRouteEndpoint(requireNotNull(server.syncHost()), requireNotNull(server.syncPort()), "vless")))
}
