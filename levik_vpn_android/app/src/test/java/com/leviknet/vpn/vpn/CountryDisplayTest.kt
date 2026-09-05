package com.leviknet.vpn.vpn

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CountryDisplayTest {
    @Test
    fun `formats flag and localized country name`() {
        assertEquals("🇫🇮 Finland", countryDisplay("fi", Locale.ENGLISH))
        assertEquals(
            "🇫🇮 Финляндия",
            countryDisplay("FI", Locale.forLanguageTag("ru")),
        )
    }

    @Test
    fun `unknown country uses globe`() {
        assertEquals("🌐", countryDisplay("XX", Locale.ENGLISH))
    }
}
