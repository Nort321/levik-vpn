package com.leviknet.vpn.vpn

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build

/**
 * Which apps Levik Game may carry: those the system knows as games. A browser or a proxy
 * client never qualifies, and neither does an app with a VPN of its own.
 */
object GameApps {
    @Suppress("DEPRECATION")
    fun isGame(info: ApplicationInfo): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_GAME) ||
            info.flags and ApplicationInfo.FLAG_IS_GAME != 0

    /** Packages that declare a VPN service (VPN clients, browsers with a VPN, remote desktops). */
    fun vpnPackages(packageManager: PackageManager): Set<String> = try {
        packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
            .mapNotNullTo(mutableSetOf()) { it.serviceInfo?.packageName }
    } catch (_: RuntimeException) {
        emptySet()
    }

    /** The installed games among [packages], without [ownPackage] and without VPN apps. */
    fun eligible(packageManager: PackageManager, packages: Set<String>, ownPackage: String): Set<String> {
        val vpn = vpnPackages(packageManager)
        return packages.filterTo(mutableSetOf()) { pkg ->
            pkg != ownPackage && pkg !in vpn && try {
                isGame(packageManager.getApplicationInfo(pkg, 0))
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
