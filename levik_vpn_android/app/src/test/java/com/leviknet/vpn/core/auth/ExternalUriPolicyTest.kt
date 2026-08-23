package com.leviknet.vpn.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalUriPolicyTest {
    @Test
    fun `allows only telegram and correctly bounded leviknet hosts`() {
        assertTrue(ExternalUriPolicy.isAllowedHttpsHost("t.me"))
        assertTrue(ExternalUriPolicy.isAllowedHttpsHost("leviknet.com"))
        assertTrue(ExternalUriPolicy.isAllowedHttpsHost("account.leviknet.com"))
        assertTrue(ExternalUriPolicy.isAllowedHttpsHost("ACCOUNT.LEVIKNET.COM"))

        assertFalse(ExternalUriPolicy.isAllowedHttpsHost("notleviknet.com"))
        assertFalse(ExternalUriPolicy.isAllowedHttpsHost("leviknet.com.example.org"))
        assertFalse(ExternalUriPolicy.isAllowedHttpsHost("telegram.me"))
    }
}
