package com.leviknet.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import libXray.ProcessFinder

/** Android exposes connection ownership only to the currently active VPN. */
internal class AndroidProcessFinder(context: Context) : ProcessFinder {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun findProcessByConnection(
        network: String?, srcIP: String?, srcPort: Long, destIP: String?, destPort: Long,
    ): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            srcPort !in 1..65535 || destPort !in 1..65535 ||
            srcIP.isNullOrBlank() || destIP.isNullOrBlank()) return -1
        val protocol = when (network?.lowercase()) {
            "tcp", "tcp4", "tcp6" -> OsConstants.IPPROTO_TCP
            "udp", "udp4", "udp6" -> OsConstants.IPPROTO_UDP
            else -> return -1
        }
        // Core supplies numeric addresses. Reject names instead of performing DNS from the callback.
        if (!srcIP.all { it in "0123456789abcdefABCDEF:.%" } ||
            !destIP.all { it in "0123456789abcdefABCDEF:.%" }) return -1
        return runCatching {
            connectivity?.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByName(srcIP), srcPort.toInt()),
                InetSocketAddress(InetAddress.getByName(destIP), destPort.toInt()),
            )?.toLong() ?: -1L
        }.getOrDefault(-1L)
    }
}

internal fun geminiAppRouting(context: Context): GeminiAppRouting {
    fun uid(packageName: String): Int? = try {
        context.packageManager.getApplicationInfo(packageName, 0).uid.takeIf { it > 0 }
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }
    return GeminiAppRouting(
        geminiUid = uid("com.google.android.apps.bard"),
        googleAppUid = uid("com.google.android.googlequicksearchbox"),
    )
}
