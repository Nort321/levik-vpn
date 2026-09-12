package com.leviknet.vpn.core.notification

import com.leviknet.vpn.core.network.AccountUser
import com.leviknet.vpn.core.network.FreeProxySummary
import com.leviknet.vpn.core.network.MobileAccountResponse
import com.leviknet.vpn.core.network.MobileSupportReply
import com.leviknet.vpn.core.network.MobileSupportSummary
import com.leviknet.vpn.core.network.TrialSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportNotificationManagerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val sampleReply = MobileSupportReply(
        replyId = "rep-123",
        ticketId = "tick-456",
        reference = "TICK-456",
        subject = "Help with connection",
        messageSnippet = "Our team has resolved the issue.",
        createdAt = "2026-09-12T12:00:00Z",
        ticketUrl = "https://leviknet.com/dashboard/support/tick-456",
    )

    @Test
    fun `shouldNotify returns false when latestReply is null`() {
        val summary = MobileSupportSummary(
            unreadCount = 1,
            latestReply = null,
        )

        assertFalse(SupportNotificationManager.shouldNotify(summary, lastNotifiedReplyId = null))
    }

    @Test
    fun `shouldNotify returns false when unreadCount is zero or negative`() {
        val zeroUnread = MobileSupportSummary(
            unreadCount = 0,
            latestReply = sampleReply,
        )
        assertFalse(SupportNotificationManager.shouldNotify(zeroUnread, lastNotifiedReplyId = null))

        val negativeUnread = MobileSupportSummary(
            unreadCount = -1,
            latestReply = sampleReply,
        )
        assertFalse(SupportNotificationManager.shouldNotify(negativeUnread, lastNotifiedReplyId = null))
    }

    @Test
    fun `shouldNotify returns false when reply was already notified`() {
        val summary = MobileSupportSummary(
            unreadCount = 1,
            latestReply = sampleReply,
        )

        assertFalse(
            SupportNotificationManager.shouldNotify(
                support = summary,
                lastNotifiedReplyId = "rep-123",
            ),
        )
    }

    @Test
    fun `shouldNotify returns true when new reply exists and has unread count`() {
        val summary = MobileSupportSummary(
            unreadCount = 2,
            latestReply = sampleReply,
        )

        // Never notified before
        assertTrue(SupportNotificationManager.shouldNotify(summary, lastNotifiedReplyId = null))

        // Different reply previously notified
        assertTrue(SupportNotificationManager.shouldNotify(summary, lastNotifiedReplyId = "rep-older"))
    }

    @Test
    fun `resolveSupportUrl returns payload ticketUrl when present and non-blank`() {
        val replyWithCustomUrl = sampleReply.copy(
            ticketUrl = "https://leviknet.com/dashboard/support/tickets/456",
        )

        assertEquals(
            "https://leviknet.com/dashboard/support/tickets/456",
            SupportNotificationManager.resolveSupportUrl(replyWithCustomUrl),
        )
    }

    @Test
    fun `resolveSupportUrl falls back to default support dashboard when ticketUrl is null or blank`() {
        val replyWithNullUrl = sampleReply.copy(ticketUrl = null)
        assertEquals(
            SupportNotificationManager.DEFAULT_SUPPORT_URL,
            SupportNotificationManager.resolveSupportUrl(replyWithNullUrl),
        )

        val replyWithBlankUrl = sampleReply.copy(ticketUrl = "   ")
        assertEquals(
            SupportNotificationManager.DEFAULT_SUPPORT_URL,
            SupportNotificationManager.resolveSupportUrl(replyWithBlankUrl),
        )
    }

    @Test
    fun `deserializes MobileAccountResponse with support summary`() {
        val rawJson = """
            {
                "ok": true,
                "user": { "userKey": "test-key", "userLabel": "test@example.com" },
                "trial": { "eligible": false, "status": "none" },
                "referrals": null,
                "subscriptions": [],
                "orders": [],
                "freeProxy": { "available": false, "active": false },
                "support": {
                    "unreadCount": 3,
                    "latestReply": {
                        "replyId": "r-789",
                        "ticketId": "t-101",
                        "reference": "REF-101",
                        "subject": "DNS leak check",
                        "messageSnippet": "Everything is secured.",
                        "createdAt": "2026-09-12T14:30:00Z",
                        "ticketUrl": "https://leviknet.com/dashboard/support/t-101"
                    }
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<MobileAccountResponse>(rawJson)

        assertTrue(response.ok)
        val support = response.support
        assertNotNull(support)
        assertEquals(3, support?.unreadCount)
        assertEquals("r-789", support?.latestReply?.replyId)
        assertEquals("t-101", support?.latestReply?.ticketId)
        assertEquals("REF-101", support?.latestReply?.reference)
        assertEquals("DNS leak check", support?.latestReply?.subject)
        assertEquals("Everything is secured.", support?.latestReply?.messageSnippet)
        assertEquals("https://leviknet.com/dashboard/support/t-101", support?.latestReply?.ticketUrl)
    }

    @Test
    fun `deserializes MobileAccountResponse when support is absent or null`() {
        val rawJsonWithoutSupport = """
            {
                "ok": true,
                "user": { "userKey": "test-key", "userLabel": "test@example.com" },
                "trial": { "eligible": false, "status": "none" },
                "referrals": null,
                "subscriptions": [],
                "orders": [],
                "freeProxy": { "available": false, "active": false }
            }
        """.trimIndent()

        val response = json.decodeFromString<MobileAccountResponse>(rawJsonWithoutSupport)
        assertTrue(response.ok)
        assertNull(response.support)

        val rawJsonWithNullSupport = """
            {
                "ok": true,
                "user": { "userKey": "test-key", "userLabel": "test@example.com" },
                "trial": { "eligible": false, "status": "none" },
                "referrals": null,
                "subscriptions": [],
                "orders": [],
                "freeProxy": { "available": false, "active": false },
                "support": null
            }
        """.trimIndent()

        val responseWithNull = json.decodeFromString<MobileAccountResponse>(rawJsonWithNullSupport)
        assertTrue(responseWithNull.ok)
        assertNull(responseWithNull.support)
    }

    @Test
    fun `channel and notification constants match requirements`() {
        assertEquals("levik_support_alerts", SupportNotificationManager.CHANNEL_ID)
        assertEquals(2005, SupportNotificationManager.NOTIFICATION_ID)
        assertEquals("https://leviknet.com/dashboard/support", SupportNotificationManager.DEFAULT_SUPPORT_URL)
    }
}
