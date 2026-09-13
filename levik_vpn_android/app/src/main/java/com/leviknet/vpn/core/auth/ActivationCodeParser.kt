package com.leviknet.vpn.core.auth

internal object ActivationCodeParser {
    private val ACTIVATION_CODE = Regex("[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}")

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

    private fun String.normalize(): String =
        java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKC).uppercase()
}
