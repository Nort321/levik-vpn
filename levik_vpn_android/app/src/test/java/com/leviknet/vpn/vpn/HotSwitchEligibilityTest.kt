package com.leviknet.vpn.vpn

import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotSwitchEligibilityTest {
    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val server = TunnelServer("one", "out-one", "Germany", "DE", buildJsonObject {
        put("tag", "out-one"); put("protocol", "vless")
    })
    private val profile = PreparedTunnelProfile(1, "profile", "subscription", "2026-10-29T00:00:00Z", servers = listOf(server))

    @Test fun `retains exact installed ordinary servers but never admits a new endpoint into the live pool`() {
        val other = server.copy(id = "new", tag = "new")
        assertEquals(listOf(server), hotSwitchEligibleServers(listOf(server), profile, profile.copy(servers = listOf(server, other)), now))
        assertTrue(hotSwitchEligibleServers(listOf(server), profile, profile.copy(servers = listOf(server.copy(outbound = buildJsonObject {
            put("tag", "out-one"); put("protocol", "vless"); put("settings", "changed credentials")
        }))), now).isEmpty())
    }

    @Test fun `rejects missing replaced and expired authority`() {
        assertTrue(hotSwitchEligibleServers(listOf(server), profile, null, now).isEmpty())
        assertTrue(hotSwitchEligibleServers(listOf(server), profile, profile.copy(subscriptionId = "other"), now).isEmpty())
        assertTrue(hotSwitchEligibleServers(listOf(server), profile, profile.copy(profileId = "other"), now).isEmpty())
        assertTrue(hotSwitchEligibleServers(listOf(server), profile, profile.copy(subscriptionExpiresAt = now.toString()), now).isEmpty())
        assertTrue(hotSwitchEligibleServers(listOf(server), profile, profile.copy(subscriptionExpiresAt = "invalid"), now).isEmpty())
    }

    @Test fun `excludes mobile home and non Xray engines`() {
        for (excluded in listOf(server.copy(category = TunnelServerCategory.MOBILE),
            server.copy(engine = TunnelEngineKind.LEVIK_TUIC), server.copy(name = "🏠 Home"))) {
            val value = profile.copy(servers = listOf(excluded))
            assertTrue(hotSwitchEligibleServers(listOf(excluded), value, value, now).isEmpty())
        }
    }

    @Test fun `force reconnect validates fresh target and subscription before tearing down the old tunnel`() {
        assertTrue(hotSwitchReconnectTargetExists(profile, profile.subscriptionId, server.id, now))
        org.junit.Assert.assertFalse(hotSwitchReconnectTargetExists(profile.copy(servers = emptyList()), profile.subscriptionId, server.id, now))
        org.junit.Assert.assertFalse(hotSwitchReconnectTargetExists(null, profile.subscriptionId, server.id, now))
        org.junit.Assert.assertFalse(hotSwitchReconnectTargetExists(profile, "other", server.id, now))
        org.junit.Assert.assertFalse(hotSwitchReconnectTargetExists(profile.copy(subscriptionExpiresAt = now.toString()), profile.subscriptionId, server.id, now))
    }
}
