package com.leviknet.vpn.vpn

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProfilePreparerTest {
    @Test
    fun `relay bootstrap nodes become explicit allow-list servers with shared credential`() {
        val bootstrap = RelayBootstrap(
            version = 1,
            protocol = "levik-relay-v1",
            policy = RelayPolicy(
                version = 1,
                deviceSlots = RelayDeviceSlotsPolicy.REMNAWAVE_HWID_SHARED,
                trafficAccounting = RelayTrafficAccountingPolicy.RELAY_SEPARATE,
            ),
            entitlementId = "123e4567-e89b-12d3-a456-426614174000",
            deviceId = "a".repeat(64),
            credentialId = "credential-123",
            accessToken = "ABCDEFGHJKLMNPQR",
            expiresAt = "2026-09-01T00:00:00Z",
            nodes = listOf(
                relayNode("de-1", "Germany", "DE"),
                relayNode("nl-1", "Netherlands", "NL"),
            ),
        )
        val routing = TunnelRouting(
            policyVersion = 1,
            directDomains = listOf("domain:example.org"),
        )
        val prepared = TunnelProfilePreparer(XrayRuntime(Json)).prepare(
            TunnelProfile(
                version = 2,
                engine = TunnelEngineKind.LEVIK_RELAY,
                profileId = "relay-profile",
                subscriptionId = "subscription-123",
                issuedAt = "2026-08-31T00:00:00Z",
                subscriptionExpiresAt = "2026-10-01T00:00:00Z",
                bootstrap = bootstrap,
                routing = routing,
            ),
        )

        assertEquals(listOf("relay:de-1", "relay:nl-1"), prepared.servers.map { it.id })
        assertTrue(prepared.servers.all { it.engine == TunnelEngineKind.LEVIK_RELAY })
        assertTrue(
            prepared.servers.all {
                it.category == TunnelServerCategory.MOBILE_ALLOWLIST &&
                    it.networkRequirement == TunnelNetworkRequirement.ANY
            },
        )
        assertEquals("2026-09-01T00:00:00Z", prepared.relayCredentialExpiresAt)
        assertEquals("ABCDEFGHJKLMNPQR", prepared.servers.first().relayConfig?.bootstrap?.accessToken)
        assertEquals(routing, prepared.servers.first().relayConfig?.routing)
        assertNotNull(prepared.servers.first().relayConfig?.node)
    }

    private fun relayNode(id: String, name: String, countryCode: String) = RelayNode(
        id = id,
        displayName = name,
        countryCode = countryCode,
        host = "$id.example.com",
        port = 443,
        turnFrontSni = "$id.front.example.com",
        transport = RelayTransport.TURN_DTLS,
        serverPublicKey = "A".repeat(43),
        turnHashes = listOf("abcdefghijklmnop"),
    )
}
