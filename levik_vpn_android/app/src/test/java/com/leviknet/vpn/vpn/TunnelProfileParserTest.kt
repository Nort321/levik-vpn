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
        assertEquals("vless://example", profile.source.content)
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
}
