package com.leviknet.vpn.core.sharing

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SharingInvitationTest {
    private val now = 1_800_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val invite = SharingInvitation(host = "192.168.137.1", port = 49152, password = key, name = "Телефон", expiresAt = now + 60_000)

    @Test fun `round trips full versioned invitation without owner credentials`() {
        assertEquals(invite, SharingInvitation.parse(invite.encode(), clock))
        val body = String(Base64.getUrlDecoder().decode(invite.encode().removePrefix(SharingInvitation.PREFIX)))
        assertTrue(body.contains("\"v\":1"))
        assertTrue(body.contains("\"method\":\"aes-256-gcm\""))
        assertFalse(body.contains("subscription"))
        assertFalse(body.contains("accessToken"))
        assertFalse(invite.toString().contains(key))
        assertFalse(SharingHostSession(invite, 49153, "/tmp/levik-sharing-test.sock").snapshot().toString().contains(key))
    }

    @Test fun `rejects public loopback malformed and ambiguous host addresses`() {
        for (host in listOf("8.8.8.8", "127.0.0.1", "169.254.1.1", "192.168.001.1", "192.168.1.256", "192.168.1.1.evil", "::1", "10.0.0.1:80", "2130706433")) {
            rejected(invite.copy(host = host).encode())
        }
        for (host in listOf("10.1.2.3", "172.16.2.1", "172.31.1.1", "192.168.1.1")) assertTrue(isPrivateIpv4(host))
    }

    @Test fun `rejects stale excessive life and invalid transport credentials`() {
        listOf(invite.copy(expiresAt = now), invite.copy(expiresAt = now + SharingInvitation.MAX_LIFETIME_MS + 120_001),
            invite.copy(port = 0), invite.copy(port = 65536), invite.copy(v = 2), invite.copy(method = "none"),
            invite.copy(password = "short"), invite.copy(name = "\nphone"), invite.copy(name = "x".repeat(65)), invite.copy(name = " "))
            .forEach { rejected(it.encode()) }
    }

    @Test fun `rejects extra fields query fragments padding and oversized input`() {
        val encoded = invite.encode()
        rejected("https://example.org/?invite=$encoded")
        rejected(encoded + "=")
        rejected(encoded + "?x=1")
        rejected(encoded + "#fragment")
        rejected(SharingInvitation.PREFIX + "A".repeat(2048))
        val body = String(Base64.getUrlDecoder().decode(encoded.removePrefix(SharingInvitation.PREFIX)))
        rejected(SharingInvitation.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.replace("}", ",\"subscription\":\"secret\"}").toByteArray()))
    }

    private fun rejected(value: String) {
        try { SharingInvitation.parse(value, clock); fail("Accepted invalid invitation") } catch (_: IllegalArgumentException) { }
    }
}
