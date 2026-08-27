package com.leviknet.vpn.vpn

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRoutesTest {
    @Test
    fun `legacy IPv6 route contains only global unicast space`() {
        assertEquals("2000::/3", VpnRoutes.PUBLIC_IPV6_ROUTE)
    }

    @Test
    fun `legacy routes include public internet addresses`() {
        assertTrue(isRouted("1.1.1.1"))
        assertTrue(isRouted("8.8.8.8"))
        assertTrue(isRouted("203.0.113.10"))
    }

    @Test
    fun `legacy routes exclude local and special-use destinations`() {
        listOf(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.10.20",
            "172.16.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "224.0.0.251",
            "255.255.255.255",
        ).forEach { address -> assertFalse(address, isRouted(address)) }
    }

    private fun isRouted(address: String): Boolean {
        val target = (InetAddress.getByName(address) as Inet4Address).address
        return VpnRoutes.publicIpv4Routes.any { cidr ->
            val (networkAddress, prefixLength) = VpnRoutes.splitCidr(cidr)
            val network = (InetAddress.getByName(networkAddress) as Inet4Address).address
            var remaining = prefixLength
            target.indices.all { index ->
                if (remaining <= 0) return@all true
                val bits = remaining.coerceAtMost(8)
                val mask = (0xff shl (8 - bits)) and 0xff
                remaining -= bits
                (target[index].toInt() and mask) == (network[index].toInt() and mask)
            }
        }
    }
}
