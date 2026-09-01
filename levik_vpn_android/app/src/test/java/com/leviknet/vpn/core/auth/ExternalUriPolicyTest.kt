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

    @Test
    fun `allows well formed secure payment urls after server validation`() {
        assertTrue(
            ExternalUriPolicy.isAllowedPaymentUrl(
                "https://app.platega.io/payment/abc?method=2",
            ),
        )
        assertTrue(
            ExternalUriPolicy.isAllowedPaymentUrl(
                "https://payments.example:443/payment/abc",
            ),
        )

        assertFalse(ExternalUriPolicy.isAllowedPaymentUrl("http://app.platega.io/payment/abc"))
        assertFalse(ExternalUriPolicy.isAllowedPaymentUrl("https:///payment/abc"))
        assertFalse(ExternalUriPolicy.isAllowedPaymentUrl("https://app.platega.io:8443/payment/abc"))
        assertFalse(ExternalUriPolicy.isAllowedPaymentUrl("https://user@app.platega.io/payment/abc"))
        assertFalse(ExternalUriPolicy.isAllowedPaymentUrl("https://app.platega.io/payment/abc#secret"))
    }
}
