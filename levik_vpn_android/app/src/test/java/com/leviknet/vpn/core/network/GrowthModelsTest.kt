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
    fun `family actions send only what the website accepts`() {
        assertEquals("""{"action":"overview"}""", json.encodeToString(FamilyRequest("overview")))
        assertEquals("""{"action":"remove","memberId":7}""", json.encodeToString(FamilyRequest("remove", 7)))
        assertEquals("{}", json.encodeToString(EmptyRequest()))
    }
}
