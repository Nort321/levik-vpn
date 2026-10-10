package com.leviknet.vpn.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {
    @Test
    fun `open-app links choose only a known screen`() {
        assertEquals(OpenAppTarget.PLANS, AppLinks.openAppTarget("https://leviknet.org/open-app?to=plans"))
        assertEquals(OpenAppTarget.SUPPORT, AppLinks.openAppTarget("https://LEVIKNET.COM/open-app?x=1&to=support"))
        assertEquals(OpenAppTarget.HOME, AppLinks.openAppTarget("https://leviknet.org/open-app?to=../../settings"))
        assertEquals(OpenAppTarget.HOME, AppLinks.openAppTarget("https://leviknet.org/open-app"))
    }

    @Test
    fun `open-app links from other hosts or paths are ignored`() {
        assertNull(AppLinks.openAppTarget("https://evil.example/open-app?to=plans"))
        assertNull(AppLinks.openAppTarget("http://leviknet.org/open-app?to=plans"))
        assertNull(AppLinks.openAppTarget("https://leviknet.org:8443/open-app?to=plans"))
        assertNull(AppLinks.openAppTarget("https://user@leviknet.org/open-app?to=plans"))
        assertNull(AppLinks.openAppTarget("https://leviknet.org/dashboard?to=plans"))
        assertNull(AppLinks.openAppTarget("https://leviknet.org/open-app?to=plans" + "a".repeat(400)))
    }

    @Test
    fun `invite links give only the code`() {
        assertEquals("ABCD2345", AppLinks.inviteCode("https://leviknet.org/i/ABCD2345"))
        assertEquals("ABCD2345EFGH", AppLinks.inviteCode("https://leviknet.com/i/abcd2345efgh/"))
        assertNull(AppLinks.inviteCode("https://evil.example/i/ABCD2345"))
        assertNull(AppLinks.inviteCode("https://leviknet.org/i/ABCD2345?claim=1"))
        assertNull(AppLinks.inviteCode("https://leviknet.org/i/../dashboard"))
        assertNull(AppLinks.inviteCode("https://leviknet.org/i/SHORT"))
        assertNull(AppLinks.inviteCode("https://leviknet.org/open-app?to=plans"))
    }

    @Test
    fun `handoff links must be the one-time page on the website`() {
        val token = "A".repeat(42) + "_"
        assertTrue(AppLinks.isHandoffUrl("https://leviknet.org/handoff?token=$token"))
        assertFalse(AppLinks.isHandoffUrl("https://leviknet.org/handoff?token=short"))
        assertFalse(AppLinks.isHandoffUrl("https://leviknet.org/handoff?token=$token&next=https://evil.example"))
        assertFalse(AppLinks.isHandoffUrl("https://evil.example/handoff?token=$token"))
        assertFalse(AppLinks.isHandoffUrl("https://leviknet.org/handoff?token=$token#frag"))
    }

    @Test
    fun `fallback opens the cabinet page without signing in`() {
        assertEquals("https://leviknet.org/dashboard/plans", AppLinks.cabinetFallbackUrl(CabinetTarget.PLANS))
    }
}
