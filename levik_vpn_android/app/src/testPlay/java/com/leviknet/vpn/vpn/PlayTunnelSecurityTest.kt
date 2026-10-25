package com.leviknet.vpn.vpn

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayTunnelSecurityTest {
    @Test
    fun `accepts generated service routes while still rejecting a plaintext remote exit`() {
        fun server(id: String, host: String, security: String) = TunnelServer(
            id, id, id, "DE", Json.parseToJsonElement("""{
                "tag":"$id","protocol":"vless","settings":{"vnext":[{
                    "address":"$host","port":443,"users":[{
                        "id":"11111111-1111-4111-8111-111111111111","encryption":"none"
                    }]
                }]},"streamSettings":{"network":"tcp","security":"$security"}
            }""").jsonObject,
        )
        val primary = server("primary", "192.0.2.1", "tls")
        val secondary = server("secondary", "192.0.2.2", "tls")
        val policy = ServiceRouteProfile(
            1, 1, "a".repeat(64),
            listOf(ServiceRouteRule("youtube", listOf("domain:youtube.com"), ServiceRouteTarget("country", "NL"), ServiceRouteTarget("block"), "service", "YouTube")),
            listOf(
                ServiceRouteExit("primary", "Primary", "DE", "enabled", "ordinary", listOf(ServiceRouteEndpoint("192.0.2.1", 443, "vless"))),
                ServiceRouteExit("secondary", "Secondary", "NL", "enabled", "ordinary", listOf(ServiceRouteEndpoint("192.0.2.2", 443, "vless"))),
            ),
        )
        val profile = PreparedTunnelProfile(1, "test", "subscription", "2026-10-25T00:00:00Z", servers = listOf(primary, secondary), serviceRoutes = policy)
        fun configuration(source: PreparedTunnelProfile): String {
            val session = requireNotNull(ServiceRouteConfig.prepare(source, primary, File("/tmp")))
            return XrayConfigBuilder(Json).build(source, primary.id, 42, serviceRouteSession = session)
        }

        PlayTunnelSecurity.validate(configuration(profile))
        val plaintext = profile.copy(servers = listOf(primary, server("secondary", "192.0.2.2", "none")))
        assertThrows(IllegalArgumentException::class.java) { PlayTunnelSecurity.validate(configuration(plaintext)) }
    }

    @Test
    fun `rejects plaintext vless and socks endpoints`() {
        for (protocol in listOf("vless", "socks")) {
            assertThrows(IllegalArgumentException::class.java) {
                PlayTunnelSecurity.validate("""{"outbounds":[{"protocol":"$protocol","settings":{}}]}""")
            }
        }
    }

    @Test
    fun `validates fallback endpoints as well as primary`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayTunnelSecurity.validate("""{"outbounds":[
                {"protocol":"vless","streamSettings":{"security":"reality"}},
                {"protocol":"vless","streamSettings":{"security":"none"}}
            ]}""")
        }
    }

    @Test
    fun `accepts encrypted transport and intentional direct routing`() {
        for (security in listOf("tls", "reality")) {
            PlayTunnelSecurity.validate("""{"outbounds":[
                {"protocol":"vless","streamSettings":{"security":"$security"}},
                {"protocol":"freedom"},{"protocol":"blackhole"}
            ]}""")
        }
    }

    @Test
    fun `rejects disabled certificate verification including nested fallback transport`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayTunnelSecurity.validate("""{"outbounds":[{"protocol":"vless","streamSettings":{
                "security":"tls","tlsSettings":{"allowInsecure":true}
            }}]}""")
        }
    }

    @Test
    fun `accepts native vmess encryption in both supported config shapes`() {
        PlayTunnelSecurity.validate("""{"outbounds":[{"protocol":"vmess","settings":{"security":"auto"}}]}""")
        PlayTunnelSecurity.validate("""{"outbounds":[{"protocol":"vmess","settings":{
            "vnext":[{"users":[{"security":"aes-128-gcm"}]}]
        }}]}""")
    }

    @Test
    fun `rejects unencrypted vmess users and shadowsocks methods`() {
        for (settings in listOf("""{"security":"none"}""", """{"vnext":[{"users":[{"security":"zero"}]}]}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                PlayTunnelSecurity.validate("""{"outbounds":[{"protocol":"vmess","settings":$settings}]}""")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlayTunnelSecurity.validate("""{"outbounds":[{"protocol":"shadowsocks","settings":{"method":"none"}}]}""")
        }
    }

    @Test
    fun `accepts shadowsocks AEAD encryption`() {
        PlayTunnelSecurity.validate("""{"outbounds":[{"protocol":"shadowsocks","settings":{
            "servers":[{"method":"2022-blake3-aes-128-gcm"}]
        }}]}""")
    }
}
