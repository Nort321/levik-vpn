package com.leviknet.vpn.core.auth

internal object ActivationCodeParser {
    private val ACTIVATION_CODE = Regex("[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}")
    /** Shown under a signed-in device's pairing QR; one group shorter than an activation code. */
    private val PAIRING_CODE = Regex("[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){2}")

    fun parseQr(rawValue: String): String? {
        val trimmed = rawValue.trim()
        return if (DeepLinkRouter.pairingToken(trimmed) != null) trimmed else parse(trimmed)
    }

    fun parse(rawValue: String): String? {
        val trimmed = rawValue.trim()
        val candidate = if (trimmed.startsWith("https://", ignoreCase = true)) {
            DeepLinkRouter.activationCode(trimmed)
        } else {
            trimmed
        } ?: return null
        return candidate.normalize().takeIf(ACTIVATION_CODE::matches)
    }

    /** A code a new device shows, typed with spaces, lower case or without dashes. */
    fun parseTyped(rawValue: String): String? = regroup(rawValue, groups = 4)?.takeIf(ACTIVATION_CODE::matches)

    /** The code a signed-in device shows under its pairing QR, typed on the new device. */
    fun parsePairingCode(rawValue: String): String? = regroup(rawValue, groups = 3)?.takeIf(PAIRING_CODE::matches)

    private fun regroup(rawValue: String, groups: Int): String? {
        val characters = rawValue.normalize().filter { it.isLetterOrDigit() }
        if (characters.length != groups * 4) return null
        return characters.chunked(4).joinToString("-")
    }

    private fun String.normalize(): String =
        java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKC).uppercase()
}
