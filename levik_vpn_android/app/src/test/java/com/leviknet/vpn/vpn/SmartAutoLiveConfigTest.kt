package com.leviknet.vpn.vpn

import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoLiveConfigTest {
    private fun server(tag: String, host: String) = TunnelServer(tag, tag, tag, "DE", Json.parseToJsonElement(
        """{"tag":"$tag","protocol":"vless","settings":{"address":"$host","port":443}}""",
    ).jsonObject)
    private val a = server("foo", "192.0.2.1")
    private val b = server("foobar", "192.0.2.2")
    private fun base(): JsonObject = Json.parseToJsonElement("""{
      "env":{"tunfd":"17"},"dns":{"servers":["1.1.1.1"]},
      "inbounds":[{"tag":"levik-tun-in","protocol":"tun"}],
      "outbounds":[{"tag":"foo","protocol":"vless"},{"tag":"foobar","protocol":"vless"},{"tag":"levik-direct","protocol":"freedom"},{"tag":"levik-block","protocol":"blackhole"}],
      "routing":{"rules":[
        {"type":"field","inboundTag":["levik-tun-in"],"ip":["::/0"],"outboundTag":"levik-block"},
        {"type":"field","inboundTag":["levik-tun-in"],"domain":["full:direct.example"],"outboundTag":"levik-direct"},
        {"type":"field","inboundTag":["levik-tun-in"],"domain":["full:proxy.example"],"outboundTag":"foo"}
      ]}
    }""").jsonObject

    @Test fun `ordinary Auto without account rules keeps one TUN and steers main through a safe alias`() {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "sa-live-").toFile()
        try { SmartAutoLiveConfig(listOf(a, b), a.id, folder, null).use { config ->
            val result = config.apply(base())
            assertEquals(base()["env"], result["env"])
            assertEquals(1, result.getValue("inbounds").jsonArray.count { it.jsonObject["protocol"] == JsonPrimitive("tun") })
            val balancers = result.getValue("routing").jsonObject.getValue("balancers").jsonArray
            assertEquals(JsonArray(listOf(JsonPrimitive(config.initial.tag))), balancers.single().jsonObject["selector"])
            val tags = result.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("tag") }
            assertTrue(JsonPrimitive("foo") in tags && JsonPrimitive("foobar") in tags)
            assertTrue(config.candidates.map { it.tag.length }.distinct().size == 1)
            assertTrue(config.candidates.none { left -> config.candidates.any { right -> left != right && right.tag.startsWith(left.tag) } })
            val rules = result.getValue("routing").jsonObject.getValue("rules").jsonArray
            assertTrue(rules.any { it.jsonObject["domain"] == JsonArray(listOf(JsonPrimitive("full:direct.example"))) && it.jsonObject["outboundTag"] == JsonPrimitive("levik-direct") })
            assertTrue(rules.any { it.jsonObject["ip"] == JsonArray(listOf(JsonPrimitive("::/0"))) && it.jsonObject["outboundTag"] == JsonPrimitive("levik-block") })
            assertEquals(JsonPrimitive(ServiceRouteConfig.MAIN), rules.last().jsonObject["balancerTag"])
            assertFalse(tags.any { (it as JsonPrimitive).content.contains("udp-loop") })
        } } finally { folder.delete() }
    }

    @Test fun `private probes bypass user direct rules and do not share the user TUN counter tag`() {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "sa-stats-").toFile()
        try { SmartAutoLiveConfig(listOf(a, b), a.id, folder, null).use { config ->
            val result = config.apply(base())
            val rules = result.getValue("routing").jsonObject.getValue("rules").jsonArray
            for ((index, candidate) in config.candidates.withIndex()) {
                assertEquals(JsonPrimitive(candidate.tag), rules.first { it.jsonObject["inboundTag"] == JsonArray(listOf(JsonPrimitive("levik-sa-live-in-$index"))) }.jsonObject["outboundTag"])
            }
            val services = result.getValue("api").jsonObject.getValue("services").jsonArray
            assertTrue(JsonPrimitive("StatsService") in services)
            assertTrue(JsonPrimitive("RoutingService") in services)
            val system = result.getValue("policy").jsonObject.getValue("system").jsonObject
            assertEquals(JsonPrimitive(true), system["statsInboundUplink"])
            assertEquals(JsonPrimitive(true), system["statsInboundDownlink"])
        } } finally { folder.delete() }
    }

    @Test fun `last acknowledged alias is restored after a legitimate core reconnect`() {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "sa-resume-").toFile()
        try { SmartAutoLiveConfig(listOf(a, b), a.id, folder, null).use { config ->
            config.installed(b.id)
            val result = config.apply(base())
            val main = result.getValue("routing").jsonObject.getValue("balancers").jsonArray.single().jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(config.candidates.first { it.id == b.id }.tag))), main["selector"])
        } } finally { folder.delete() }
    }

    @Test fun `account service rules and automatic main share one API and one main owner`() {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "sa-rules-").toFile()
        val exits = listOf(a, b).map { source -> ServiceRouteExit(source.id, source.name, source.countryCode, "enabled", "ordinary", listOf(ServiceRouteEndpoint(source.syncHost()!!, 443, "vless"))) }
        val rule = ServiceRouteRule("main-rule", listOf("full:service.example"), ServiceRouteTarget("main"), ServiceRouteTarget("block"), "domain", "Service")
        val policy = ServiceRouteProfile(1, 1, "a".repeat(64), listOf(rule), exits)
        val profile = PreparedTunnelProfile(1, "test", "subscription", "2026-10-28T00:00:00Z", servers = listOf(a, b), serviceRoutes = policy)
        val service = ServiceRouteConfig.prepare(profile, a, folder, true)!!
        try { SmartAutoLiveConfig(listOf(a, b), a.id, folder, service).use { config ->
            val result = config.apply(service.apply(base()))
            assertFalse(config.ownsControl)
            assertEquals(service.controlPath, config.controlPath)
            val inbounds = result.getValue("inbounds").jsonArray
            assertEquals(1, inbounds.count { it.jsonObject["tag"] == JsonPrimitive("levik-sr-api-in") })
            val balancers = result.getValue("routing").jsonObject.getValue("balancers").jsonArray
            assertEquals(1, balancers.count { it.jsonObject["tag"] == JsonPrimitive(ServiceRouteConfig.MAIN) })
            assertTrue(balancers.any { it.jsonObject["tag"] == JsonPrimitive(ServiceRouteConfig.routeTag(0)) })
        } } finally { service.close(); folder.delete() }
    }
}
