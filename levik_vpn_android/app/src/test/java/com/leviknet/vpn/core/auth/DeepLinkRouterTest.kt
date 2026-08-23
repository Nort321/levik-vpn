package com.leviknet.vpn.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkRouterTest {
    @Test
    fun `accepts only bounded activation code on canonical https route`() {
        assertEquals(
            DeepLinkDestination.ACTIVATION,
            DeepLinkRouter.route("https://leviknet.com/activate?code=Abc_1234-xyz"),
        )
        assertEquals(
            "Abc_1234-xyz",
            DeepLinkRouter.activationCode("https://leviknet.com/activate?code=Abc_1234-xyz"),
        )
    }

    @Test
    fun `rejects legacy custom scheme and credential parameters`() {
        assertNull(DeepLinkRouter.route("levikvpn://auth?token=secret-value"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate?token=secret-value"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate?auth=secret-value"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate?connect=true"))
    }

    @Test
    fun `rejects lookalike hosts subdomains and non-canonical paths`() {
        assertNull(DeepLinkRouter.route("https://notleviknet.com/activate?code=Abc_1234-xyz"))
        assertNull(DeepLinkRouter.route("https://login.leviknet.com/activate?code=Abc_1234-xyz"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/app?code=Abc_1234-xyz"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate/?code=Abc_1234-xyz"))
    }

    @Test
    fun `rejects malformed duplicate and out of bounds codes`() {
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate?code=short"))
        assertNull(
            DeepLinkRouter.route(
                "https://leviknet.com/activate?code=${"a".repeat(257)}",
            ),
        )
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate?code=Abc_1234-xyz&code=other-code"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate?code=Abc%2F1234"))
    }

    @Test
    fun `rejects user info explicit ports and fragments`() {
        assertNull(DeepLinkRouter.route("https://user@leviknet.com/activate?code=Abc_1234-xyz"))
        assertNull(DeepLinkRouter.route("https://leviknet.com:443/activate?code=Abc_1234-xyz"))
        assertNull(DeepLinkRouter.route("https://leviknet.com/activate?code=Abc_1234-xyz#fragment"))
    }
}
