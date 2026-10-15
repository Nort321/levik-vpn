package com.leviknet.vpn.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrowthModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `reads the website family answer for an owner`() {
        val response = json.decodeFromString<FamilyResponse>(
            """
            {"ok":true,"family":{"available":true,"seats":4,"role":"owner","familyId":3,
             "expiresAt":"2026-12-01T00:00:00Z","ownerName":"Мария","usedSeats":2,
             "members":[{"id":1,"role":"owner","name":"Вы","devices":3,"lastActiveAt":null,"joinedAt":"2026-10-01"}]},
             "invite":{"url":"https://leviknet.org/i/ABCD2345EFGH","expiresAt":"2026-10-14T00:00:00Z"}}
            """.trimIndent(),
        )
        assertEquals("owner", response.family.role)
        assertEquals(1, response.family.members.size)
        assertEquals("https://leviknet.org/i/ABCD2345EFGH", response.invite?.url)
    }

    @Test
    fun `a member sees no member list`() {
        val response = json.decodeFromString<FamilyResponse>(
            """{"ok":true,"family":{"available":true,"seats":4,"role":"member","usedSeats":2}}""",
        )
        assertEquals(emptyList<FamilyMember>(), response.family.members)
        assertNull(response.invite)
    }

    @Test
    fun `reads who allowed help`() {
        val member = json.decodeFromString<FamilyResponse>(
            """{"ok":true,"family":{"available":true,"seats":4,"role":"member","usedSeats":2,"helpAllowed":true}}""",
        )
        assertEquals(true, member.family.helpAllowed)
        val help = json.decodeFromString<FamilyHelpResponse>(
            """
            {"ok":true,"members":[{"memberId":7,"name":"Анна","lastActiveAt":null,"linked":true,
              "devices":[{"ref":"6f1c2a52-8a0e-4c3d-9b8f-1d2e3f4a5b6c","name":"Pixel 8","platform":"android",
                "appVersion":"2.8.0","remote":true,"online":false,"vpnConnected":false}]}],
             "commands":[{"id":"00000000-0000-4000-8000-000000000009","memberId":7,
               "deviceRef":"6f1c2a52-8a0e-4c3d-9b8f-1d2e3f4a5b6c","deviceName":"Pixel 8","command":"check_update",
               "state":"delivered","detail":null,"createdAt":"2026-10-15T10:00:00Z"}]}
            """.trimIndent(),
        )
        assertEquals("Pixel 8", help.members.single().devices.single().name)
        assertEquals(false, help.latestFor("6f1c2a52-8a0e-4c3d-9b8f-1d2e3f4a5b6c")?.final)
    }

    @Test
    fun `family actions send only what the website accepts`() {
        assertEquals("""{"action":"overview"}""", json.encodeToString(FamilyRequest("overview")))
        assertEquals("""{"action":"remove","memberId":7}""", json.encodeToString(FamilyRequest("remove", 7)))
        assertEquals("""{"action":"help","allowed":true}""", json.encodeToString(FamilyRequest("help", allowed = true)))
        assertEquals(
            """{"memberId":7,"deviceRef":"6f1c2a52-8a0e-4c3d-9b8f-1d2e3f4a5b6c","command":"auto_server"}""",
            json.encodeToString(FamilyHelpRequest(7, "6f1c2a52-8a0e-4c3d-9b8f-1d2e3f4a5b6c", "auto_server")),
        )
        assertEquals("{}", json.encodeToString(EmptyRequest()))
    }
}
