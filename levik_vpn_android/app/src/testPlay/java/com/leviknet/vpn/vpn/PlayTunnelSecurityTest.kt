package com.leviknet.vpn.vpn

import org.junit.Assert.assertThrows
import org.junit.Test

class PlayTunnelSecurityTest {
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
