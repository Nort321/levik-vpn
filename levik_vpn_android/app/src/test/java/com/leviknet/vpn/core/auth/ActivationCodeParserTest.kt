package com.leviknet.vpn.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationCodeParserTest {
    @Test
    fun `parses canonical activation URL and normalizes raw codes`() {
        assertEquals(
            "ABCD-EFGH-JKMN-PQRS",
            ActivationCodeParser.parse("https://leviknet.com/activate?code=ABCD-EFGH-JKMN-PQRS"),
        )
        assertEquals(
            "ABCD-EFGH-JKMN-PQRS",
            ActivationCodeParser.parse("  abcd-efgh-jkmn-pqrs  "),
        )
    }

    @Test
    fun `rejects secrets malformed values and untrusted URLs`() {
        assertNull(ActivationCodeParser.parse("https://evil.example/activate?code=ABCD-EFGH-JKMN-PQRS"))
        assertNull(ActivationCodeParser.parse("https://leviknet.com/activate?token=secret"))
        assertNull(ActivationCodeParser.parse("ABCD-EFGH"))
        assertNull(ActivationCodeParser.parse("ABCI-EFGH-JKMN-PQRS"))
    }
}
