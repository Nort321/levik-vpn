package com.leviknet.vpn.vpn

import java.util.Locale

fun countryFlag(countryCode: String?): String {
    val code = countryCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
    if (code.length != 2 || !code.all { it in 'A'..'Z' } || code == "XX") return "🌐"
    val codePoints = code.map { 0x1F1E6 + (it - 'A') }.toIntArray()
    return String(codePoints, 0, codePoints.size)
}

fun localizedCountryName(countryCode: String?, locale: Locale = Locale.getDefault()): String? {
    val code = countryCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
    if (code.length != 2 || !code.all { it in 'A'..'Z' } || code == "XX") return null
    return Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
        .takeIf(String::isNotBlank)
}

fun countryDisplay(countryCode: String?, locale: Locale = Locale.getDefault()): String {
    val name = localizedCountryName(countryCode, locale)
    return if (name == null) countryFlag(countryCode) else "${countryFlag(countryCode)} $name"
}
