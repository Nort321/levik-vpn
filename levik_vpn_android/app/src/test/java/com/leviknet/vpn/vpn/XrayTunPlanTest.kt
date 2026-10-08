package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayTunPlanTest {
    @Test
    fun `advertises reachable IPv4 resolvers while retaining IPv6 leak protection`() {
        val plan = xrayTunPlan("9.9.9.9", "149.112.112.112")
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), plan.dnsServers)
        assertTrue(plan.addresses.any { it.address.contains(':') })
        assertEquals(XrayConfigBuilder.TUN_MTU, plan.mtu)
    }

    @Test
    fun `duplicate custom resolver is advertised once`() {
        assertEquals(listOf("1.1.1.1"), xrayTunPlan("1.1.1.1", "1.1.1.1").dnsServers)
    }

    @Test
    fun `IPv4 only Yandex plan never enables IPv6 through an address or resolver`() {
        val plan = xrayTunPlan("1.1.1.1", "1.0.0.1", ipv6Enabled = false)
        assertFalse(plan.ipv6Enabled)
        assertEquals(listOf(TunAddress("172.30.0.2", 30)), plan.addresses)
        assertTrue(plan.dnsServers.all { ':' !in it })
    }

    @Test
    fun `IPv4 only plan rejects an IPv6 address or DNS that would enable the family`() {
        assertThrows(IllegalArgumentException::class.java) {
            xrayTunPlan("2606:4700:4700::1111", "1.0.0.1", ipv6Enabled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunPlan(1280, listOf(TunAddress("2001:db8::2", 64)), listOf("1.1.1.1"), ipv6Enabled = false)
        }
    }
}
