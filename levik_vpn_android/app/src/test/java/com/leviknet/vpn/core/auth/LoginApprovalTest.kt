package com.leviknet.vpn.core.auth

import com.leviknet.vpn.core.network.AuthChallengeRequest
import com.leviknet.vpn.core.network.AuthStatusResponse
import com.leviknet.vpn.core.network.LoginState
import com.leviknet.vpn.core.network.MobileApiClient
import com.leviknet.vpn.core.network.PendingApprovalWire
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginApprovalTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val approval = PendingApprovalWire(
        id = "0f8e2a64-5c1b-4d3e-9a7f-1b2c3d4e5f60",
        deviceName = "MacBook Pro",
        platformLabel = "macOS",
        choices = listOf(37, 52, 81),
        expiresAt = "2026-10-14T12:05:00Z",
    )

    // The app's own Json: nulls left out, defaults written.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `accepts only well-formed account hints`() {
        assertTrue(LoginApproval.isAccountHint("v1.${"a".repeat(16)}.${"b".repeat(40)}.${"c".repeat(22)}"))
        assertFalse(LoginApproval.isAccountHint("v2.${"a".repeat(16)}.${"b".repeat(40)}.${"c".repeat(22)}"))
        assertFalse(LoginApproval.isAccountHint("v1.${"a".repeat(16)}.b+c/.${"c".repeat(22)}"))
        assertFalse(LoginApproval.isAccountHint(null))
    }

    @Test
    fun `keeps two-digit numbers only`() {
        assertEquals(37, LoginApproval.matchNumber(37))
        listOf(9, 100, null).forEach { assertNull(LoginApproval.matchNumber(it)) }
    }

    @Test
    fun `parses approvals and drops expired or malformed ones`() {
        val parsed = LoginApproval.parse(
            listOf(
                approval,
                approval.copy(id = "not-a-uuid"),
                approval.copy(choices = listOf(37, 37, 52)),
                approval.copy(choices = listOf(37, 52)),
                approval.copy(choices = listOf(37, 52, 100)),
                approval.copy(expiresAt = "2026-10-14T11:59:00Z"),
                approval.copy(deviceName = " "),
                PendingApprovalWire(),
            ),
            now,
        )
        assertEquals(
            listOf(
                PendingApproval(
                    id = "0f8e2a64-5c1b-4d3e-9a7f-1b2c3d4e5f60",
                    deviceName = "MacBook Pro",
                    platformLabel = "macOS",
                    choices = listOf(37, 52, 81),
                    expiresAt = Instant.parse("2026-10-14T12:05:00Z"),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun `cleans control and bidi characters out of device names`() {
        val parsed = LoginApproval.parse(listOf(approval.copy(deviceName = "Evil‮ PC\n\tname")), now)
        assertEquals("Evil PC name", parsed.single().deviceName)
    }

    @Test
    fun `accepts one of the offered numbers or a decline`() {
        val parsed = LoginApproval.parse(listOf(approval), now).single()
        assertTrue(LoginApproval.isAnswer(parsed, 52))
        assertTrue(LoginApproval.isAnswer(parsed, null))
        assertFalse(LoginApproval.isAnswer(parsed, 53))
    }

    @Test
    fun `a decline sends an explicit null number`() {
        assertEquals(
            "{\"id\":\"0f8e2a64-5c1b-4d3e-9a7f-1b2c3d4e5f60\",\"number\":null}",
            MobileApiClient.approvalDecisionBody("0f8e2a64-5c1b-4d3e-9a7f-1b2c3d4e5f60", null),
        )
        assertEquals(
            "{\"id\":\"0f8e2a64-5c1b-4d3e-9a7f-1b2c3d4e5f60\",\"number\":37}",
            MobileApiClient.approvalDecisionBody("0f8e2a64-5c1b-4d3e-9a7f-1b2c3d4e5f60", 37),
        )
    }

    @Test
    fun `older requests leave the number-matching fields out`() {
        val request = AuthChallengeRequest(
            accountActivationSupported = true,
            publicKeySpki = "key",
            deviceLabel = "Pixel",
            deviceModel = "Pixel",
            deviceOs = "Android 16",
            appVersion = "2.7.28",
            requestSigningAlgorithm = "PS256",
            profileEncryptionAlgorithm = "RSA-OAEP-256+A256GCM",
        )
        val plain = json.encodeToString(request)
        assertFalse(plain.contains("numberMatchSupported"))
        assertFalse(plain.contains("accountHint"))
        val matching = json.encodeToString(request.copy(numberMatchSupported = true))
        assertTrue(matching.contains("\"numberMatchSupported\":true"))
        assertFalse(matching.contains("accountHint"))
    }

    @Test
    fun `reads the declined state and the account hint`() {
        assertEquals(LoginState.DECLINED, LoginState.fromWire("declined"))
        val status = json.decodeFromString<AuthStatusResponse>(
            "{\"ok\":true,\"state\":\"authenticated\",\"accessToken\":\"t\",\"accountHint\":\"v1.x\"}",
        )
        assertEquals("v1.x", status.accountHint)
    }

    @Test
    fun `opens only the bot's sign-in link, in the Telegram app`() {
        val start = "web_${"x".repeat(43)}"
        assertEquals(
            "tg://resolve?domain=levikvpnbot&start=$start",
            AuthorizationChallengePolicy.telegramStartUri("https://t.me/levikvpnbot?start=$start"),
        )
        assertNull(AuthorizationChallengePolicy.telegramStartUri("http://t.me/levikvpnbot?start=$start"))
        assertNull(AuthorizationChallengePolicy.telegramStartUri("https://t.me.example.net/levikvpnbot?start=$start"))
        assertNull(AuthorizationChallengePolicy.telegramStartUri("https://t.me/otherbot?start=$start"))
        assertNull(AuthorizationChallengePolicy.telegramStartUri("https://t.me/levikvpnbot?start=$start#x"))
    }
}
