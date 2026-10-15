package com.leviknet.vpn.core.platform

import com.leviknet.vpn.vpn.PreparedTunnelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountRulesTest {
    @Test
    fun `finds the site in a shared link or text`() {
        assertEquals("youtube.com", SharedSite.from("https://www.youtube.com/watch?v=abc"))
        assertEquals("m.youtube.com", SharedSite.from("Смотри: https://m.youtube.com/watch?v=abc — классно"))
        assertEquals("kinopoisk.ru", SharedSite.from("Фильм\nhttp://KinoPoisk.ru/film/1/"))
        assertEquals("example.org", SharedSite.from("example.org/path"))
        assertEquals("xn--d1abbgf6aiiy.xn--p1ai", SharedSite.from("https://президент.рф/"))
    }

    @Test
    fun `finds no site where there is none`() {
        for (text in listOf(null, "", "просто текст", "file:///sdcard/a.txt", "https://192.168.1.1/", "https://localhost/", "tel:+70000000000")) {
            assertNull(text, SharedSite.from(text))
        }
    }

    @Test
    fun `sends the change the server expects, a reset category with an explicit null`() {
        assertEquals(
            """{"op":"set_category","category":"banks","action":null}""",
            RuleChange.SetCategory("banks", null).body(),
        )
        assertEquals(
            """{"op":"set_category","category":"banks","action":"direct"}""",
            RuleChange.SetCategory("banks", RuleAction.DIRECT).body(),
        )
        assertEquals(
            """{"op":"set_domain","domain":"youtube.com","action":"proxy"}""",
            RuleChange.SetDomain("youtube.com", RuleAction.PROXY).body(),
        )
        assertEquals("""{"op":"remove_domain","domain":"youtube.com"}""", RuleChange.RemoveDomain("youtube.com").body())
    }

    @Test
    fun `a running VPN restarts only when the rules routing changed`() {
        val profile = PreparedTunnelProfile(
            version = 1,
            profileId = "p",
            subscriptionId = "s",
            issuedAt = "2026-10-15T00:00:00Z",
            servers = emptyList(),
            directDomains = listOf("domain:sberbank.ru"),
        )
        assertEquals(RuleRoutingKey.of(profile), RuleRoutingKey.of(profile.copy(profileId = "q", issuedAt = "2026-10-15T00:01:00Z")))
        assertNotEquals(RuleRoutingKey.of(profile), RuleRoutingKey.of(profile.copy(directApps = listOf("ru.sberbankmobile"))))
        assertNotEquals(RuleRoutingKey.of(profile), RuleRoutingKey.of(profile.copy(proxyDomains = listOf("domain:youtube.com"))))
        assertNull(RuleRoutingKey.of(null))
    }
}
