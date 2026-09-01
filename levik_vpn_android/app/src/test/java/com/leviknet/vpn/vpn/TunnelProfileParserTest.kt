package com.leviknet.vpn.vpn

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TunnelProfileParserTest {
    private val now = Instant.parse("2026-07-29T12:00:00Z")
    private val parser = TunnelProfileParser(
        json = Json { ignoreUnknownKeys = true },
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `accepts bounded source profile for requested subscription`() {
        val profile = parser.parse(
            plaintext = profileJson(
                issuedAt = "2026-07-29T11:59:00Z",
                subscriptionExpiresAt = "2026-08-29T13:00:00Z",
            ).encodeToByteArray(),
            expectedSubscriptionId = "subscription-123",
        )

        assertEquals("profile-123", profile.profileId)
        assertEquals("vless://example", requireNotNull(profile.source).content)
        assertEquals(listOf("domain:example.org"), profile.routing?.directDomains)
        assertEquals(listOf("domain:blocked.example"), profile.routing?.proxyDomains)
    }

    @Test
    fun `accepts long lived entitlement without treating it as cache ttl`() {
        val profile = parser.parse(
            plaintext = profileJson(
                issuedAt = "2026-07-29T11:59:00Z",
                subscriptionExpiresAt = "2027-07-29T12:00:00Z",
            ).encodeToByteArray(),
            expectedSubscriptionId = "subscription-123",
        )

        assertEquals("2027-07-29T12:00:00Z", profile.subscriptionExpiresAt)
    }

    @Test
    fun `rejects subscription mismatch`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                plaintext = profileJson(
                    issuedAt = "2026-07-29T11:59:00Z",
                    subscriptionExpiresAt = "2026-08-29T13:00:00Z",
                ).encodeToByteArray(),
                expectedSubscriptionId = "another-subscription",
            )
        }
    }

    @Test
    fun `accepts strict relay v2 bootstrap for this device`() {
        val profile = parser.parse(
            plaintext = relayProfileJson().encodeToByteArray(),
            expectedSubscriptionId = "subscription-123",
            expectedDeviceId = DEVICE_ID,
        )

        assertEquals(TunnelEngineKind.LEVIK_RELAY, profile.engine)
        assertEquals("relay-node-1", requireNotNull(profile.bootstrap).nodes.single().id)
        assertEquals("front.example.com", profile.bootstrap.nodes.single().turnFrontSni)
        assertEquals(listOf("abcdefghijklmnop"), profile.bootstrap.nodes.single().turnHashes)
    }

    @Test
    fun `rejects ambiguous legacy relay serverName metadata`() {
        val profile = relayProfileJson().replace("turnFrontSni", "serverName")

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                plaintext = profile.encodeToByteArray(),
                expectedSubscriptionId = "subscription-123",
                expectedDeviceId = DEVICE_ID,
            )
        }
    }

    @Test
    fun `play-compatible parser rejects relay profile engine`() {
        val xrayOnlyParser = TunnelProfileParser(
            json = Json { ignoreUnknownKeys = true },
            clock = Clock.fixed(now, ZoneOffset.UTC),
            supportedEngines = setOf(TunnelEngineKind.XRAY),
        )

        assertThrows(IllegalArgumentException::class.java) {
            xrayOnlyParser.parse(
                plaintext = relayProfileJson().encodeToByteArray(),
                expectedSubscriptionId = "subscription-123",
                expectedDeviceId = DEVICE_ID,
            )
        }
    }

    @Test
    fun `rejects relay bootstrap bound to another device`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                plaintext = relayProfileJson().encodeToByteArray(),
                expectedSubscriptionId = "subscription-123",
                expectedDeviceId = "b".repeat(64),
            )
        }
    }

    @Test
    fun `rejects relay node credential containing a URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                plaintext = relayProfileJson(turnHash = "https://vk.example/call/join/abcdefgh")
                    .encodeToByteArray(),
                expectedSubscriptionId = "subscription-123",
                expectedDeviceId = DEVICE_ID,
            )
        }
    }

    @Test
    fun `rejects unknown relay profile fields even when shared JSON is permissive`() {
        val profile = relayProfileJson().replace(
            "\"profileId\": \"relay-profile-123\",",
            "\"profileId\": \"relay-profile-123\",\n          \"unexpected\": true,",
        )

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                plaintext = profile.encodeToByteArray(),
                expectedSubscriptionId = "subscription-123",
                expectedDeviceId = DEVICE_ID,
            )
        }
    }

    @Test
    fun `accepts short relay credential for lifetime subscription`() {
        val profile = parser.parse(
            plaintext = relayProfileJson(subscriptionExpiresAt = null).encodeToByteArray(),
            expectedSubscriptionId = "subscription-123",
            expectedDeviceId = DEVICE_ID,
        )

        assertEquals(null, profile.subscriptionExpiresAt)
    }

    @Test
    fun `rejects relay credential longer than seven days`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                plaintext = relayProfileJson(credentialExpiresAt = "2026-08-10T12:00:00Z")
                    .encodeToByteArray(),
                expectedSubscriptionId = "subscription-123",
                expectedDeviceId = DEVICE_ID,
            )
        }
    }

    private fun profileJson(issuedAt: String, subscriptionExpiresAt: String): String =
        """
        {
          "version": 1,
          "profileId": "profile-123",
          "subscriptionId": "subscription-123",
          "issuedAt": "$issuedAt",
          "subscriptionExpiresAt": "$subscriptionExpiresAt",
          "source": {
            "mediaType": "text/plain",
            "content": "vless://example"
          },
          "routing": {
            "policyVersion": 3,
            "directCidrs": ["192.0.2.0/24"],
            "directDomains": ["domain:example.org"],
            "proxyDomains": ["domain:blocked.example"]
          }
        }
        """.trimIndent()

    private fun relayProfileJson(
        turnHash: String = "abcdefghijklmnop",
        subscriptionExpiresAt: String? = "2026-08-29T13:00:00Z",
        credentialExpiresAt: String = "2026-07-30T12:00:00Z",
    ): String {
        val subscriptionExpiryField = subscriptionExpiresAt?.let { value ->
            "\"subscriptionExpiresAt\": \"$value\","
        } ?: "\"subscriptionExpiresAt\": null,"
        return """
        {
          "version": 2,
          "engine": "levik-relay",
          "profileId": "relay-profile-123",
          "subscriptionId": "subscription-123",
          "issuedAt": "2026-07-29T11:59:00Z",
          $subscriptionExpiryField
          "bootstrap": {
            "version": 1,
            "protocol": "levik-relay-v1",
            "policy": {
              "version": 1,
              "deviceSlots": "remnawave-hwid-shared",
              "trafficAccounting": "relay-separate"
            },
            "entitlementId": "123e4567-e89b-12d3-a456-426614174000",
            "deviceId": "$DEVICE_ID",
            "credentialId": "credential-123",
            "accessToken": "ABCDEFGHJKLMNPQR",
            "expiresAt": "$credentialExpiresAt",
            "nodes": [{
              "id": "relay-node-1",
              "displayName": "Mobile Allow-list 1",
              "countryCode": "DE",
              "host": "relay.example.com",
              "port": 443,
              "turnFrontSni": "front.example.com",
              "transport": "turn-dtls",
              "serverPublicKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "turnHashes": ["$turnHash"]
            }]
          },
          "routing": {
            "policyVersion": 1,
            "directCidrs": [],
            "directDomains": ["domain:example.org"],
            "proxyDomains": []
          }
        }
        """.trimIndent()
    }

    private companion object {
        val DEVICE_ID = "a".repeat(64)
    }
}
