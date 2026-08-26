package com.leviknet.vpn.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/** Stable, privacy-preserving input used only to enforce the one-trial-per-device rule. */
class TrialDeviceBinding(private val context: Context) {
    fun value(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        require(androidId.isNotBlank()) { "Android device identifier is unavailable" }
        val certificate = signingCertificate()
        return sha256(
            "levik-trial-binding-v1\u0000$androidId\u0000${certificate.toHex()}"
                .encodeToByteArray(),
        ).toHex()
    }

    @Suppress("DEPRECATION")
    private fun signingCertificate(): ByteArray {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = requireNotNull(packageInfo.signingInfo)
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            requireNotNull(packageInfo.signatures)
        }
        return requireNotNull(
            signatures
                .map { it.toByteArray() }
                .minByOrNull { sha256(it).toHex() },
        ) { "Application signing certificate is unavailable" }
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
