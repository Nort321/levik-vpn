package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.security.validateYandexProfileBinding
import com.leviknet.vpn.data.mergeYandexProfile
import com.leviknet.vpn.data.hasUsableYandexAuth
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexProfileTest {
    private val now = Instant.parse("2026-10-08T06:00:00Z")
    private val deviceId = "a".repeat(64)
    private val subscription = "subscription-123"
    private val token = jwt("""{"document":{"key":"document-key==","permissions":{"edit":true}}}""")
    private val auth = YandexProviderAuth("https://opaque_label.onlyoffice.disk.yandex.net", token, now.epochSecond + 600)
    private val bootstrap = YandexBootstrap(1, deviceId, "https://disk.yandex.ru/i/abcdefghijk",
        "A".repeat(43), "b".repeat(64), now.epochSecond + 900, auth)
    private val profile = TunnelProfile(3, TunnelEngineKind.LEVIK_YANDEX, "profile-123", subscription,
        now.toString(), now.plusSeconds(86400).toString(), yandexBootstrap = bootstrap, routing = TunnelRouting())
    private val parser = TunnelProfileParser(Json { ignoreUnknownKeys = true }, Clock.fixed(now, ZoneOffset.UTC))

    @Test fun `cached guest access must leave enough time for proactive refresh`() {
        val server = TunnelServer("yandex:document", "yandex:document", "Через Яндекс", "NL", JsonObject(emptyMap()),
            engine = TunnelEngineKind.LEVIK_YANDEX, yandexConfig = YandexServerConfig(bootstrap))
        assertTrue(server.hasUsableYandexAuth(now))
        for (remaining in listOf(-1L, 0L, 30L, 90L, 180L)) {
            val nearExpiry = bootstrap.copy(providerAuth = auth.copy(validUntil = now.epochSecond + remaining))
            assertFalse(server.copy(yandexConfig = YandexServerConfig(nearExpiry)).hasUsableYandexAuth(now))
            val nearLeaseExpiry = bootstrap.copy(expiresAt = now.epochSecond + remaining)
            assertFalse(server.copy(yandexConfig = YandexServerConfig(nearLeaseExpiry)).hasUsableYandexAuth(now))
        }
        assertTrue(server.copy(yandexConfig = YandexServerConfig(bootstrap.copy(
            providerAuth = auth.copy(validUntil = now.epochSecond + 181),
        ))).hasUsableYandexAuth(now))
    }

    @Test fun `refresh starts immediately near expiry and retries until original deadline`() {
        assertEquals(420_000L, yandexAuthRefreshDelayMillis(600_000, attempted = false))
        assertEquals(1_000L, yandexAuthRefreshDelayMillis(181_000, attempted = false))
        for (remaining in listOf(180_000L, 90_000L, 30_000L, 1L)) {
            assertEquals(0L, yandexAuthRefreshDelayMillis(remaining, attempted = false))
        }
        assertEquals(30_000L, yandexAuthRefreshDelayMillis(90_000, attempted = true))
        assertEquals(10_000L, yandexAuthRefreshDelayMillis(10_000, attempted = true))
        assertNull(yandexAuthRefreshDelayMillis(0, attempted = false))
        assertNull(yandexAuthRefreshDelayMillis(-1, attempted = true))
    }

    @Test fun `provider rollover preserves ownership and key rotation requires reconnect`() {
        val current = YandexServerConfig(bootstrap)
        val next = current.copy(bootstrap = bootstrap.copy(providerAuth = auth.copy(
            balancerUrl = "https://replacement.onlyoffice.disk.yandex.net",
            token = jwt("""{"document":{"key":"replacement-key","permissions":{"edit":true}}}"""),
            validUntil = auth.validUntil + 30,
        )))
        assertEquals(YandexProfileRefreshMode.HOT_REFRESH, yandexProfileRefreshMode(current, next))
        assertEquals(YandexProfileRefreshMode.RECONNECT, yandexProfileRefreshMode(current,
            next.copy(bootstrap = next.bootstrap.copy(sharedKey = "c".repeat(64)))))
        for (foreign in listOf(bootstrap.copy(deviceId = "c".repeat(64)),
            bootstrap.copy(leaseRef = "B".repeat(43)), bootstrap.copy(version = 2),
            bootstrap.copy(documentUrl = "https://disk.yandex.ru/i/another-document"))) {
            assertEquals(YandexProfileRefreshMode.REJECT, yandexProfileRefreshMode(current, current.copy(bootstrap = foreign)))
        }
        assertEquals(YandexProfileRefreshMode.REJECT, yandexProfileRefreshMode(current,
            current.copy(routing = TunnelRouting(proxyDomains = listOf("example.com")))))
    }

    @Test fun `accepts only public document URLs and exact provider authority`() {
        YandexContract.validateDocumentUrl(bootstrap.documentUrl)
        YandexContract.validateProviderAuth(auth, now)
        listOf("http://disk.yandex.ru/i/abcdefgh", "https://disk.yandex.ru/i/abcdefgh?x=1",
            "https://disk.yandex.ru/i/abcdefgh#fragment", "https://disk.yandex.ru@evil.example/i/abcdefgh",
            "https://disk.yandex.ru:443/i/abcdefgh", "https://disk.yandex.ru/i/abc%2fdefgh")
            .forEach { url -> assertThrows(IllegalArgumentException::class.java) { YandexContract.validateDocumentUrl(url) } }
        listOf("https://onlyoffice.disk.yandex.net.evil.example", "https://user@onlyoffice.disk.yandex.net",
            "https://onlyoffice.disk.yandex.net:443", "https://a.b.onlyoffice.disk.yandex.net",
            "http://onlyoffice.disk.yandex.net", "https://onlyoffice.disk.yandex.net/path")
            .forEach { url -> assertThrows(IllegalArgumentException::class.java) {
                YandexContract.validateProviderAuth(auth.copy(balancerUrl = url), now)
            } }
    }

    @Test fun `accepts profile3 only with exclusive device bound bootstrap`() {
        assertEquals(profile, parse(profile))
        listOf(profile.copy(source = TunnelProfileSource("text/plain", "wrong")),
            profile.copy(engine = TunnelEngineKind.XRAY),
            profile.copy(yandexBootstrap = bootstrap.copy(deviceId = "f".repeat(64))),
            profile.copy(yandexBootstrap = bootstrap.copy(expiresAt = now.epochSecond)),
            profile.copy(yandexBootstrap = bootstrap.copy(sharedKey = "x".repeat(64))),
            profile.copy(yandexBootstrap = bootstrap.copy(providerAuth = auth.copy(validUntil = now.epochSecond + 901))))
            .forEach { bad -> assertThrows(IllegalArgumentException::class.java) { parse(bad) } }
    }

    @Test fun `rejects unknown profile fields and unsupported Play engine`() {
        val encoded = encoded(profile).replaceFirst("{", "{\"unknown\":true,")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(encoded.toByteArray(), subscription, deviceId) }
        val playParser = TunnelProfileParser(Json, Clock.fixed(now, ZoneOffset.UTC), setOf(TunnelEngineKind.XRAY))
        assertThrows(IllegalArgumentException::class.java) { playParser.parse(encoded(profile).toByteArray(), subscription, deviceId) }
    }

    @Test fun `AAD matches device subscription lease and profile after authenticated decrypt`() {
        val aad = listOf("levik-mobile-yandex-profile-v3", deviceId,
            "12345678-1234-4234-8234-123456789012", subscription, bootstrap.leaseRef, profile.profileId).joinToString("\n")
        validateYandexProfileBinding(base64(aad), profile, deviceId)
        listOf(aad.replace(subscription, "different"), aad + "\n", aad.replace(deviceId, "f".repeat(64)),
            aad.replace(bootstrap.leaseRef, "B".repeat(43))).forEach {
            assertThrows(IllegalArgumentException::class.java) { validateYandexProfileBinding(base64(it), profile, deviceId) }
        }
    }

    @Test fun `guest config refuses account identity ownership read only and other editors`() {
        val config = """{"user":{},"officeActionData":{"is_owner":false,"balancer_url":"${auth.balancerUrl}","editor_config":{"token":"$token","document":{"permissions":{"edit":true}}}}}"""
        assertEquals(auth, parseYandexGuestConfig(config, now))
        listOf(config.replace("\"user\":{}", "\"user\":{\"auth\":true}"),
            config.replace("\"user\":{}", "\"user\":{\"uid\":123}"),
            config.replace("\"is_owner\":false", "\"is_owner\":true"),
            config.replace("\"edit\":true", "\"edit\":false"),
            config.replace("officeActionData", "volgaConfig")).forEach {
            assertThrows(IllegalArgumentException::class.java) { parseYandexGuestConfig(it, now) }
        }
        val shorterToken = jwt("""{"exp":${now.epochSecond + 120},"document":{"key":"document-key==","permissions":{"edit":true}}}""")
        assertEquals(now.epochSecond + 120, parseYandexGuestConfig(config.replace(token, shorterToken), now).validUntil)
    }

    @Test fun `profile merge retains existing engines without requiring an Xray fetch`() {
        val yandex = PreparedTunnelProfile(3, "profile", subscription, now.toString(), servers = listOf(
            TunnelServer("yandex:document", "yandex:document", "Через Яндекс", "NL", JsonObject(emptyMap()),
                engine = TunnelEngineKind.LEVIK_YANDEX, yandexConfig = YandexServerConfig(bootstrap))))
        assertEquals(yandex, mergeYandexProfile(null, yandex))
        val xray = yandex.copy(servers = listOf(TunnelServer("xray", "xray", "VPN", "NL", JsonObject(emptyMap()))))
        assertEquals(listOf(TunnelEngineKind.XRAY, TunnelEngineKind.LEVIK_YANDEX),
            mergeYandexProfile(xray, yandex).servers.map { it.engine })
        assertThrows(IllegalArgumentException::class.java) { mergeYandexProfile(xray.copy(subscriptionId = "other"), yandex) }
    }

    private fun encoded(value: TunnelProfile): String = Json.encodeToString(TunnelProfile.serializer(), value)
    private fun parse(value: TunnelProfile) = parser.parse(encoded(value).toByteArray(), subscription, deviceId)
    private fun jwt(payload: String) = base64("""{"alg":"HS256","typ":"JWT"}""") + "." + base64(payload) + "." + "A".repeat(43)
    private fun base64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
