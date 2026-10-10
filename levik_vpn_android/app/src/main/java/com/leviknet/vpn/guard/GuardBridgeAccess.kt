package com.leviknet.vpn.guard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * Explicit local consent for sharing aggregate VPN status with Levik Guard.
 *
 * A package name alone proves nothing: any app can call itself com.leviknet.guard when the
 * real one is not installed. When the person turns the bridge on, the signing certificate of
 * the installed Guard is pinned, and later calls are answered only for that certificate
 * (or a newer one in its rotation lineage).
 */
object GuardBridgeAccess {
    const val GUARD_PACKAGE = "com.leviknet.guard"

    private const val PREFERENCES = "guard_bridge"
    private const val ENABLED = "enabled"
    private const val PINNED_CERTIFICATES = "pinned_certificates_sha256"

    fun isEnabled(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        // Consent given before pinning existed must be given again.
        return preferences.getBoolean(ENABLED, false) &&
            !preferences.getStringSet(PINNED_CERTIFICATES, null).isNullOrEmpty()
    }

    /** Returns whether the bridge is on afterwards; it stays off while Guard is not installed. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val certificates = if (enabled) signingCertificates(context, GUARD_PACKAGE) else emptySet()
        val active = enabled && certificates.isNotEmpty()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putBoolean(ENABLED, active)
            if (active) putStringSet(PINNED_CERTIFICATES, certificates) else remove(PINNED_CERTIFICATES)
        }
        return active
    }

    fun isTrustedCaller(context: Context, callerUid: Int): Boolean {
        if (!isEnabled(context)) return false
        val packages = context.packageManager.getPackagesForUid(callerUid).orEmpty()
        if (GUARD_PACKAGE !in packages) return false
        val pinned = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(PINNED_CERTIFICATES, null).orEmpty()
        return signingCertificates(context, GUARD_PACKAGE).any(pinned::contains)
    }

    @Suppress("DEPRECATION")
    internal fun signingCertificates(context: Context, packageName: String): Set<String> {
        val manager = context.packageManager
        val signatures = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo ?: return emptySet()
                // Several signers must all be present, so they are not a rotation lineage.
                if (info.hasMultipleSigners()) return emptySet()
                info.signingCertificateHistory
            } else {
                manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return emptySet()
        } ?: return emptySet()
        return signatures.mapTo(linkedSetOf()) { sha256Hex(it.toByteArray()) }
    }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
