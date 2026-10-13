package com.leviknet.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayVkLoginPageTest {
    @Test
    fun `recognizes pages that ask for a VK login`() {
        assertTrue(isVkLoginPage("https://id.vk.com/auth?app_id=1"))
        assertTrue(isVkLoginPage("https://login.vk.ru/?act=login"))
        assertTrue(isVkLoginPage("https://m.vk.com/login?to=call"))
        assertTrue(isVkLoginPage("https://m.vk.com/join/login"))
    }

    @Test
    fun `keeps the call join page and unrelated URLs`() {
        assertFalse(isVkLoginPage("https://m.vk.com/call/join/abcdefghijklmnop"))
        assertFalse(isVkLoginPage("https://vk.com/feed"))
        assertFalse(isVkLoginPage("not a url"))
    }
}
