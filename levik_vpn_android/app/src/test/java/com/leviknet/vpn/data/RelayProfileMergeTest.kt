package com.leviknet.vpn.data

import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.DeviceSummary
import com.leviknet.vpn.core.network.SubscriptionActions
import com.leviknet.vpn.core.network.SubscriptionCapabilities
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.core.network.TrafficSummary
import com.leviknet.vpn.vpn.PreparedTunnelProfile
import com.leviknet.vpn.vpn.RelayBootstrap
import com.leviknet.vpn.vpn.RelayDeviceSlotsPolicy
import com.leviknet.vpn.vpn.RelayNode
import com.leviknet.vpn.vpn.RelayPolicy
import com.leviknet.vpn.vpn.RelayServerConfig
import com.leviknet.vpn.vpn.RelayTrafficAccountingPolicy
import com.leviknet.vpn.vpn.RelayTransport
import com.leviknet.vpn.vpn.TunnelEngineKind
import com.leviknet.vpn.vpn.TunnelNetworkRequirement
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.TunnelServerCategory
import java.time.Instant
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProfileMergeTest {
    @Test
    fun `xray and relay profiles merge without changing automatic xray server`() {
        val xray = profile(
            version = 1,
            profileId = "xray-profile",
            expiry = "2026-10-01T00:00:00Z",
            servers = listOf(server("xray-1", TunnelEngineKind.XRAY)),
        )
        val relay = profile(
            version = 2,
            profileId = "relay-profile",
            expiry = "2026-09-30T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )

        val first = mergePreparedProfiles(xray, relay)
        val second = mergePreparedProfiles(xray, relay)

        assertEquals(first.profileId, second.profileId)
        assertTrue(first.profileId.startsWith("composite:"))
        assertEquals(listOf("xray-1", "relay:de-1"), first.servers.map { it.id })
        assertEquals("2026-09-30T00:00:00Z", first.subscriptionExpiresAt)
        assertEquals("2026-09-01T00:00:00Z", first.relayCredentialExpiresAt)
    }

    @Test
    fun `relay merge keeps TUIC servers of the regular profile`() {
        val xray = profile(
            version = 1,
            profileId = "xray-profile",
            expiry = "2026-10-01T00:00:00Z",
            servers = listOf(
                server("xray-1", TunnelEngineKind.XRAY),
                server("tuic-1", TunnelEngineKind.LEVIK_TUIC),
            ),
        )
        val relay = profile(
            version = 2,
            profileId = "relay-profile",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )

        assertEquals(
            listOf("xray-1", "tuic-1", "relay:de-1"),
            mergePreparedProfiles(xray, relay).servers.map { it.id },
        )
    }

    @Test
    fun `temporary relay refresh failure may retain only an unexpired cached credential`() {
        val cached = profile(
            version = 2,
            profileId = "cached",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(
                server("xray-1", TunnelEngineKind.XRAY),
                server("relay:de-1", TunnelEngineKind.LEVIK_RELAY),
            ),
        )

        assertTrue(hasUsableRelayCredential(cached, Instant.parse("2026-08-31T00:00:00Z")))
        assertFalse(hasUsableRelayCredential(cached, Instant.parse("2026-09-01T00:00:00Z")))
        val sanitized = withoutRelayServers(cached)
        assertEquals(listOf("xray-1"), sanitized.servers.map { it.id })
        assertEquals(null, sanitized.relayCredentialExpiresAt)
    }

    @Test
    fun `relay access refresh is required before expiry and without a usable relay credential`() {
        val profile = profile(
            version = 2,
            profileId = "relay-profile",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )
        assertFalse(relayProfileRefreshDue(profile, Instant.parse("2026-08-31T23:54:59Z")))
        assertTrue(relayProfileRefreshDue(profile, Instant.parse("2026-08-31T23:55:00Z")))
        assertTrue(relayProfileRefreshDue(profile, Instant.parse("2026-09-01T00:00:00Z")))
        assertTrue(relayProfileRefreshDue(withoutRelayServers(profile), Instant.parse("2026-08-31T00:00:00Z")))
        assertFalse(
            relayProfileRefreshDue(
                profile.copy(relayCredentialExpiresAt = null),
                Instant.parse("2026-08-31T23:54:59Z"),
            ),
        )
    }

    @Test
    fun `cached metadata cannot hide an expired bootstrap or missing relay config`() {
        val profile = profile(
            version = 2,
            profileId = "relay-profile",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-02T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )
        val now = Instant.parse("2026-09-01T00:00:00Z")
        assertFalse(hasUsableRelayCredential(profile, now))
        assertTrue(relayProfileRefreshDue(profile, now))
        assertFalse(
            hasUsableRelayCredential(profile.copy(servers = profile.servers.map { it.copy(relayConfig = null) }), now),
        )
    }

    @Test
    fun `relay-only refresh preserves regular and yandex profiles while replacing expired access`() {
        val cached = profile(
            version = 3,
            profileId = "cached-profile",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-08-31T12:00:00Z",
            servers = listOf(
                server("xray-1", TunnelEngineKind.XRAY),
                server("tuic-1", TunnelEngineKind.LEVIK_TUIC),
                server("yandex:document", TunnelEngineKind.LEVIK_YANDEX),
                server("relay:de-1", TunnelEngineKind.LEVIK_RELAY, "2026-08-31T12:00:00Z"),
            ),
        )
        val renewed = profile(
            version = 2,
            profileId = "fresh-relay",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-2", TunnelEngineKind.LEVIK_RELAY)),
        )
        val replaced = replaceRelayProfile(cached, renewed, Instant.parse("2026-08-31T23:00:00Z"))

        assertEquals(listOf("xray-1", "tuic-1", "yandex:document", "relay:de-2"), replaced.servers.map { it.id })
        assertEquals(cached.servers.take(3), replaced.servers.take(3))
        assertEquals(renewed.relayCredentialExpiresAt, replaced.relayCredentialExpiresAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `relay refresh cannot persist an already expired access token`() {
        val expired = profile(
            version = 2,
            profileId = "relay-profile",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )
        replaceRelayProfile(null, expired, Instant.parse("2026-09-01T00:00:00Z"))
    }

    @Test
    fun `unknown account preserves cached authorization while known denial fails closed`() {
        val now = Instant.parse("2026-08-31T23:00:00Z")
        val active = subscription()
        val cached = profile(
            version = 2,
            profileId = "cached-relay",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )
        assertNull(relayEntitlementFailure("subscription-123", null, now))
        assertNull(relayEntitlementFailure("subscription-123", listOf(active), now))
        assertTrue(canReuseRelayCache(cached, null, now))
        assertTrue(canReuseRelayCache(cached, listOf(active), now))
        assertFalse(canReuseRelayCache(cached, emptyList(), now))
        assertFalse(canReuseRelayCache(cached, listOf(active.copy(expireAt = "2026-08-30T00:00:00Z")), now))
        assertFalse(canUseCachedRelayProfileAfter(ApiException.Network(IOException()), cached, now, emptyList()))
        assertEquals("subscription_inactive", relayEntitlementFailure("subscription-123", emptyList(), now))
        assertEquals("subscription_inactive", relayEntitlementFailure(
            "subscription-123", listOf(active.copy(status = "disabled")), now,
        ))
        assertEquals("relay_not_available", relayEntitlementFailure(
            "subscription-123", listOf(active.copy(capabilities = SubscriptionCapabilities())), now,
        ))
    }

    @Test
    fun `temporary renewal outage can reuse a current credential only until its actual expiry`() {
        val cached = profile(
            version = 2,
            profileId = "relay-profile",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )
        val now = Instant.parse("2026-08-31T23:59:59Z")
        val network = ApiException.Network(IOException())
        assertTrue(relayProfileRefreshDue(cached, now))
        assertTrue(canUseCachedRelayProfileAfter(network, cached, now))
        assertFalse(canUseCachedRelayProfileAfter(network, cached, now.plusSeconds(1)))
        assertFalse(canUseCachedRelayProfileAfter(ApiException.Unauthorized(), cached, now))
        assertFalse(canUseCachedRelayProfileAfter(
            ApiException.Rejected("relay_not_available", retryable = false, status = 403), cached, now,
        ))
        assertFalse(canUseCachedRelayProfileAfter(
            network, cached.copy(subscriptionExpiresAt = "2026-08-31T23:00:00Z"), now,
        ))
        assertTrue(isRelayAccessDenied(ApiException.Rejected("relay_not_available", false, 404)))
        assertTrue(isRelayAccessDenied(ApiException.Rejected("subscription_not_found", false, 404)))
        assertFalse(isRelayAccessDenied(ApiException.Rejected("http_404", false, 404)))
        assertFalse(isRelayAccessDenied(ApiException.Rejected("http_403", false, 403)))
        assertFalse(isRelayAccessDenied(ApiException.InvalidResponse("HTML error page")))
        assertFalse(isRelayAccessDenied(ApiException.Rejected("profile_upstream_unavailable", true, 503)))
    }

    @Test
    fun `fresh authenticated relay expiry replaces stale paid subscription expiry without exceeding its bound`() {
        val cached = profile(
            version = 2,
            profileId = "old-paid-profile",
            expiry = "2026-08-30T00:00:00Z",
            relayExpiry = "2026-08-30T00:00:00Z",
            servers = listOf(
                server("xray-1", TunnelEngineKind.XRAY),
                server("relay:de-1", TunnelEngineKind.LEVIK_RELAY, "2026-08-30T00:00:00Z"),
            ),
        )
        val renewed = profile(
            version = 2,
            profileId = "renewed-paid-profile",
            expiry = "2026-10-01T00:00:00Z",
            relayExpiry = "2026-09-01T00:00:00Z",
            servers = listOf(server("relay:de-1", TunnelEngineKind.LEVIK_RELAY)),
        )
        val now = Instant.parse("2026-08-31T23:00:00Z")
        val merged = replaceRelayProfile(cached, renewed, now)
        assertEquals(renewed.subscriptionExpiresAt, merged.subscriptionExpiresAt)
        assertEquals(listOf("xray-1", "relay:de-1"), merged.servers.map { it.id })
        assertEquals(
            renewed.subscriptionExpiresAt,
            replaceRelayProfile(cached.copy(subscriptionExpiresAt = "2027-01-01T00:00:00Z"), renewed, now).subscriptionExpiresAt,
        )
        assertThrows(IllegalArgumentException::class.java) {
            replaceRelayProfile(cached, renewed.copy(subscriptionId = "other-subscription"), now)
        }
    }

    private fun subscription() = SubscriptionSummary(
        uuid = "subscription-123",
        tariffId = "mobile",
        title = "Mobile",
        status = "active",
        expireAt = "2026-10-01T00:00:00Z",
        traffic = TrafficSummary(0, 0),
        devices = DeviceSummary(1, 1, emptyList()),
        capabilities = SubscriptionCapabilities(whitelistRelay = true),
        actions = SubscriptionActions(false, false, true, false, false),
    )

    private fun profile(
        version: Int,
        profileId: String,
        expiry: String,
        relayExpiry: String? = null,
        servers: List<TunnelServer>,
    ) = PreparedTunnelProfile(
        version = version,
        profileId = profileId,
        subscriptionId = "subscription-123",
        issuedAt = "2026-08-01T00:00:00Z",
        subscriptionExpiresAt = expiry,
        relayCredentialExpiresAt = relayExpiry,
        servers = servers,
    )

    private fun server(
        id: String,
        engine: TunnelEngineKind,
        relayExpiry: String = "2026-09-01T00:00:00Z",
    ) = TunnelServer(
        id = id,
        tag = id,
        name = id,
        countryCode = "DE",
        outbound = JsonObject(emptyMap()),
        engine = engine,
        category = if (engine == TunnelEngineKind.LEVIK_RELAY) {
            TunnelServerCategory.MOBILE_ALLOWLIST
        } else {
            TunnelServerCategory.REGULAR
        },
        networkRequirement = if (engine == TunnelEngineKind.LEVIK_RELAY) {
            TunnelNetworkRequirement.CELLULAR_ALLOWLIST
        } else {
            TunnelNetworkRequirement.ANY
        },
        relayConfig = if (engine == TunnelEngineKind.LEVIK_RELAY) relayConfig(id, relayExpiry) else null,
    )

    private fun relayConfig(id: String, expiresAt: String): RelayServerConfig {
        val node = RelayNode(id, id, "DE", "relay.example", 443, "front.example", RelayTransport.TURN_DTLS, "public-key", listOf("hash"))
        return RelayServerConfig(
            bootstrap = RelayBootstrap(
                version = 1,
                protocol = "wdtt",
                policy = RelayPolicy(1, RelayDeviceSlotsPolicy.REMNAWAVE_HWID_SHARED, RelayTrafficAccountingPolicy.RELAY_SEPARATE),
                entitlementId = "entitlement",
                deviceId = "device",
                credentialId = "credential",
                accessToken = "access-token",
                expiresAt = expiresAt,
                nodes = listOf(node),
            ),
            node = node,
        )
    }
}
