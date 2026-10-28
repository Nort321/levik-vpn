package com.leviknet.vpn.vpn

import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRouteProbeConfigTest {
    @Test fun `only proxy endpoint hostname bootstrap uses the protected physical resolver`() {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "sa-dns-").toFile()
        try {
            val server = TunnelServer("cascade", "cascade", "Node", "DE", Json.parseToJsonElement("""{"settings":{"address":"vpn.example.net","port":443}}""").jsonObject)
            SmartRouteProbeConfig(listOf(server), folder).use { config ->
                val base = Json.parseToJsonElement("""{"dns":{"queryStrategy":"UseIPv4","servers":["https://dns.example.net/dns-query","1.1.1.1"]},"inbounds":[],"outbounds":[],"routing":{"rules":[]}}""").jsonObject
                val applied = config.apply(base)
                val dns = applied.getValue("dns").jsonObject.getValue("servers").jsonArray
                val bootstrap = dns.first().jsonObject
                assertEquals(JsonPrimitive("localhost"), bootstrap["address"])
                assertEquals(listOf(JsonPrimitive("full:vpn.example.net")), bootstrap.getValue("domains").jsonArray.toList())
                assertEquals(JsonPrimitive(true), bootstrap["skipFallback"])
                assertEquals(JsonPrimitive(true), bootstrap["finalQuery"])
                assertEquals(JsonPrimitive("https://dns.example.net/dns-query"), dns[1])
                val rules = applied.getValue("routing").jsonObject.getValue("rules").jsonArray
                assertEquals(JsonPrimitive("cascade"), rules.first().jsonObject["outboundTag"])
                assertEquals(JsonPrimitive("levik-block"), rules.last().jsonObject["outboundTag"])
                assertFalse(rules.any { it.jsonObject["outboundTag"] == JsonPrimitive("levik-direct") })
            }
        } finally { folder.delete() }
    }

    @Test fun `every probe is forced through its own outbound and split routes cannot bypass it`() {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "sa-probe-").toFile()
        try {
            val servers = listOf("cascade", "direct").map { TunnelServer(it, it, it, "DE", JsonObject(emptyMap())) }
            SmartRouteProbeConfig(servers, folder).use { config ->
                val base = Json.parseToJsonElement("""{"env":{"tunfd":"123"},"inbounds":[{"protocol":"tun"}],"outbounds":[],"routing":{"rules":[{"outboundTag":"levik-direct"}]}}""").jsonObject
                val applied = config.apply(base)
                assertFalse(applied.containsKey("env"))
                val inbounds = applied.getValue("inbounds").jsonArray
                assertTrue(inbounds.all { it.jsonObject["protocol"] == JsonPrimitive("http") })
                assertTrue(inbounds.all { it.jsonObject.getValue("listen").toString().contains(",0600") })
                val rules = applied.getValue("routing").jsonObject.getValue("rules").jsonArray
                assertEquals(JsonPrimitive("cascade"), rules[0].jsonObject["outboundTag"])
                assertEquals(JsonPrimitive("direct"), rules[1].jsonObject["outboundTag"])
                assertEquals(JsonPrimitive("levik-block"), rules.last().jsonObject["outboundTag"])
                assertFalse(rules.any { it.jsonObject["outboundTag"] == JsonPrimitive("levik-direct") })
            }
        } finally { folder.delete() }
    }
}
