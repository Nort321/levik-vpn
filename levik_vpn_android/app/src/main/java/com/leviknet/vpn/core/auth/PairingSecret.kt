package com.leviknet.vpn.core.auth

/**
 * What a new device got from a signed-in one, docs/app-platform.md "Device pairing":
 * the token of a scanned QR, or the code typed where there is no camera (TV).
 */
internal sealed interface PairingSecret {
    data class Token(val value: String) : PairingSecret
    data class Code(val value: String) : PairingSecret
}
