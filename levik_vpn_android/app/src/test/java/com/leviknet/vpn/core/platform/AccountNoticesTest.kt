package com.leviknet.vpn.core.platform

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountNoticesTest {
    @Test
    fun readsNoticesAndSkipsBrokenOnes() {
        val response = Json.parseToJsonElement(
            """
            [
              {"id":"3f1c2b4a-5d6e-4f70-8a9b-0c1d2e3f4a5b","kind":"subscription_expiring","title":"Подписка закончится через 3 дня",
               "body":"Продлите заранее.","target":"/dashboard/plans","createdAt":"2026-10-15T07:00:00Z"},
              {"id":"4a2d3c5b-6e7f-4081-9bac-1d2e3f4a5b6c","kind":"weather","title":"Новое","body":"Что-то новое","target":"/somewhere"},
              {"id":"not-a-uuid","title":"x","body":"y","target":"/dashboard"},
              {"id":"5b3e4d6c-7f80-4192-acbd-2e3f4a5b6c7d","title":"","body":"y"},
              {"id":"6c4f5e7d-8091-42a3-bdce-3f4a5b6c7d8e","title":7,"body":"y"},
              "nonsense"
            ]
            """,
        )
        val notices = AccountNotices.parse(response)
        assertEquals(2, notices.size)
        assertEquals(CabinetTarget.PLANS, notices[0].target)
        // An unknown kind is shown like any other; an unknown page opens the cabinet.
        assertEquals(CabinetTarget.DASHBOARD, notices[1].target)
        assertEquals(emptyList<AccountNotice>(), AccountNotices.parse(null))
    }

    @Test
    fun opensOnlyTappedNotices() {
        assertEquals(CabinetTarget.FAMILY, AccountNotices.openedTarget(AccountNotices.ACTION_OPEN, "FAMILY"))
        assertEquals(CabinetTarget.DASHBOARD, AccountNotices.openedTarget(AccountNotices.ACTION_OPEN, "../evil"))
        assertNull(AccountNotices.openedTarget("android.intent.action.VIEW", "FAMILY"))
        assertNull(AccountNotices.openedTarget(null, null))
    }

    @Test
    fun knowsTheNotificationsEvent() {
        assertEquals(LiveEvent.NOTIFICATIONS, LiveEvent.of("notifications"))
    }
}
