package com.leviknet.vpn.data

import com.leviknet.vpn.vpn.PreparedTunnelProfile
import com.leviknet.vpn.vpn.TunnelEngineKind
import com.leviknet.vpn.vpn.TunnelNetworkRequirement
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.TunnelServerCategory
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun server(id: String, engine: TunnelEngineKind) = TunnelServer(
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
    )
}
