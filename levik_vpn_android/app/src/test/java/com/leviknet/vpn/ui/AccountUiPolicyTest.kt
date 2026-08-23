package com.leviknet.vpn.ui

import com.leviknet.vpn.core.network.MobileAccountResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountUiPolicyTest {
    @Test
    fun `pure Levik Account snapshot hides referral UI`() {
        val account = Json.decodeFromString<MobileAccountResponse>(
            snapshot(referrals = "null"),
        )

        assertNull(referralSummaryForDisplay(account))
    }

    @Test
    fun `legacy Telegram snapshot keeps referral UI`() {
        val account = Json.decodeFromString<MobileAccountResponse>(
            snapshot(
                referrals = """
                    {
                      "invited": 3,
                      "rewarded": 2,
                      "discountPercent": 10,
                      "rewardDays": 7,
                      "referralLink": "https://t.me/levikvpn_bot?start=legacy"
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(3, referralSummaryForDisplay(account)?.invited)
    }

    private fun snapshot(referrals: String): String =
        """
        {
          "ok": true,
          "user": { "userKey": "account:-1", "userLabel": "Levik Account" },
          "trial": { "eligible": false, "status": "unavailable", "expiresAt": null },
          "referrals": $referrals,
          "subscriptions": [],
          "orders": [],
          "freeProxy": { "available": false, "active": false }
        }
        """.trimIndent()
}
