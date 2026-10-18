package com.leviknet.vpn.vpn

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySessionSafetyTest {
    @Test
    fun `credential deadline cannot be extended by wall clock rollback`() {
        val wallNow = Instant.parse("2026-08-31T10:00:00Z")
        val deadline = MonotonicCredentialDeadline.create(
            expiresAt = wallNow.plusSeconds(60),
            wallClockNow = wallNow,
            elapsedRealtimeMs = 5_000L,
        )

        // Wall time is deliberately irrelevant after creation; only elapsed realtime advances.
        assertFalse(deadline.isExpired(64_999L))
        assertEquals(1L, deadline.remainingMillis(64_999L))
        assertTrue(deadline.isExpired(65_000L))
        assertEquals(0L, deadline.remainingMillis(65_001L))
    }

    @Test
    fun `already expired credential is rejected before native startup`() {
        val now = Instant.parse("2026-08-31T10:00:00Z")

        assertThrows(IllegalArgumentException::class.java) {
            MonotonicCredentialDeadline.create(
                expiresAt = now,
                wallClockNow = now,
                elapsedRealtimeMs = 100L,
            )
        }
    }

    @Test
    fun `relay renewal starts before the hard deadline with bounded watchdog rounds`() {
        assertFalse(relayCredentialRenewalDue(300_001L))
        assertTrue(relayCredentialRenewalDue(300_000L))
        assertTrue(relayCredentialRenewalDue(1L))
        assertFalse(relayCredentialRenewalDue(0L))
    }

    @Test
    fun `renewal extends access only for the same entitlement device and selected node`() {
        val previous = relayConfig()
        val renewed = previous.copy(bootstrap = previous.bootstrap.copy(
            credentialId = "renewed",
            accessToken = "renewed-access-token",
            expiresAt = "2026-09-01T12:00:00Z",
        ))
        assertTrue(relayCredentialExtendsAccess(previous, renewed))
        assertFalse(relayCredentialExtendsAccess(previous, previous))
        assertFalse(relayCredentialHasSameBinding(previous, renewed.copy(
            bootstrap = renewed.bootstrap.copy(deviceId = "another-device"),
        )))
        assertFalse(relayCredentialHasSameBinding(previous, renewed.copy(
            bootstrap = renewed.bootstrap.copy(entitlementId = "another-entitlement"),
        )))
        assertFalse(relayCredentialHasSameBinding(previous, renewed.copy(
            node = renewed.node.copy(id = "another-node"),
        )))
        assertFalse(relayCredentialExtendsAccess(previous, renewed.copy(
            bootstrap = renewed.bootstrap.copy(expiresAt = "invalid"),
        )))
    }

    private fun relayConfig(): RelayServerConfig {
        val node = RelayNode("node", "Mobile", "DE", "relay.example", 443, "front.example", RelayTransport.TURN_DTLS, "key", listOf("hash"))
        return RelayServerConfig(
            bootstrap = RelayBootstrap(
                version = 1,
                protocol = "wdtt",
                policy = RelayPolicy(1, RelayDeviceSlotsPolicy.REMNAWAVE_HWID_SHARED, RelayTrafficAccountingPolicy.RELAY_SEPARATE),
                entitlementId = "entitlement",
                deviceId = "device",
                credentialId = "credential",
                accessToken = "access-token",
                expiresAt = "2026-08-31T12:00:00Z",
                nodes = listOf(node),
            ),
            node = node,
        )
    }
}
