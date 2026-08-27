package com.leviknet.vpn.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import java.net.InetAddress

/**
 * Keeps LAN, loopback, carrier-local, link-local and multicast traffic outside the VPN.
 * Android 13+ can express exclusions directly. Older releases, and Android builds that
 * reject throw routes while establishing the interface, receive the equivalent allow-list
 * of public destinations because a default route cannot be negated there.
 */
internal object VpnRoutes {
    private val localNetworks = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "224.0.0.0/4",
        "240.0.0.0/4",
        "::/128",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
        "fec0::/10",
        "ff00::/8",
    )

    // VpnService.Builder rejects loopback destinations as invalid routes. Loopback never
    // leaves the device, so it needs no RTN_THROW entry and remains part of the compatible
    // public-route complement below.
    internal val nativeExcludedNetworks = localNetworks.filterNot { cidr ->
        cidr == "127.0.0.0/8" || cidr == "::1/128"
    }

    // 0.0.0.0/0 minus the IPv4 entries in localNetworks.
    internal val publicIpv4Routes = listOf(
        "1.0.0.0/8", "2.0.0.0/7", "4.0.0.0/6", "8.0.0.0/7",
        "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3",
        "64.0.0.0/3", "96.0.0.0/6", "100.0.0.0/10", "100.128.0.0/9",
        "101.0.0.0/8", "102.0.0.0/7", "104.0.0.0/5", "112.0.0.0/5",
        "120.0.0.0/6", "124.0.0.0/7", "126.0.0.0/8", "128.0.0.0/3",
        "160.0.0.0/5", "168.0.0.0/8", "169.0.0.0/9", "169.128.0.0/10",
        "169.192.0.0/11", "169.224.0.0/12", "169.240.0.0/13", "169.248.0.0/14",
        "169.252.0.0/15", "169.255.0.0/16", "170.0.0.0/7", "172.0.0.0/12",
        "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8",
        "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11",
        "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14",
        "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7",
        "196.0.0.0/7", "198.0.0.0/12", "198.16.0.0/15", "198.20.0.0/14",
        "198.24.0.0/13", "198.32.0.0/11", "198.64.0.0/10", "198.128.0.0/9",
        "199.0.0.0/8", "200.0.0.0/5", "208.0.0.0/4",
    )

    internal const val PUBLIC_IPV6_ROUTE = "2000::/3"

    fun apply(
        builder: VpnService.Builder,
        useNativeExclusions: Boolean = supportsNativeExclusions(),
    ) {
        if (useNativeExclusions) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            nativeExcludedNetworks.forEach { cidr ->
                val (address, prefix) = splitCidr(cidr)
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
            }
            return
        }

        publicIpv4Routes.forEach { cidr ->
            val (address, prefix) = splitCidr(cidr)
            builder.addRoute(address, prefix)
        }
        val (ipv6Address, ipv6Prefix) = splitCidr(PUBLIC_IPV6_ROUTE)
        builder.addRoute(ipv6Address, ipv6Prefix)
    }

    internal fun supportsNativeExclusions(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    internal fun shouldRetryWithCompatibleRoutes(
        usedNativeExclusions: Boolean,
        error: Throwable,
    ): Boolean = usedNativeExclusions &&
        (error is IllegalArgumentException || error is IllegalStateException)

    internal fun splitCidr(cidr: String): Pair<String, Int> {
        val separator = cidr.lastIndexOf('/')
        require(separator in 1 until cidr.lastIndex) { "Invalid CIDR" }
        return cidr.substring(0, separator) to cidr.substring(separator + 1).toInt()
    }
}
