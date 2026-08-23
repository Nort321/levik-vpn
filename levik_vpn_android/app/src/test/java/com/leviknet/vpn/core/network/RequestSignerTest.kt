package com.leviknet.vpn.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestSignerTest {
    @Test
    fun `canonical request has stable field order and no trailing newline`() {
        val canonical = RequestSigner.canonicalPayload(
            method = "POST",
            path = "/api/mobile/v1/tunnel-profile",
            timestamp = 1_700_000_000,
            nonce = "MDEyMzQ1Njc4OWFiY2RlZg",
            deviceId = "a".repeat(64),
            tokenHash = "b".repeat(64),
            bodyHash = "c".repeat(64),
        )

        assertEquals(
            listOf(
                "v1",
                "POST",
                "/api/mobile/v1/tunnel-profile",
                "1700000000",
                "MDEyMzQ1Njc4OWFiY2RlZg",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
            ).joinToString("\n"),
            canonical,
        )
    }
}
