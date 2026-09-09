package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
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
}
