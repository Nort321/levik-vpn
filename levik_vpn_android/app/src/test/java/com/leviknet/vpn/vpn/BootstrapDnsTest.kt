package com.leviknet.vpn.vpn

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapDnsTest {
    @Test
    fun `uses the first physical network resolver instead of a public default`() {
        assertEquals(
            "10.45.0.1:53",
            selectBootstrapDnsServer(listOf(address("10.45.0.1"), address("10.45.0.2"))),
        )
    }

    @Test
    fun `supports IPv6 network DNS even when the tunnel uses IPv4`() {
        val ipv6 = address("2001:db8::53")
        assertEquals("[${ipv6.hostAddress}]:53", selectBootstrapDnsServer(listOf(ipv6)))
    }

    @Test
    fun `preserves the physical interface scope for link-local IPv6 DNS`() {
        val scoped = Inet6Address.getByAddress(null, address("fe80::53").address, 7)
        assertEquals("[${scoped.hostAddress}]:53", selectBootstrapDnsServer(listOf(scoped)))
    }

    @Test
    fun `does not fall back to public DNS for a missing network resolver`() {
        assertNull(selectBootstrapDnsServer(emptyList()))
        assertNull(selectBootstrapDnsServer(listOf(address("0.0.0.0"), address("::"))))
    }

    @Test
    fun `skips loopback and multicast resolvers that cannot bootstrap a tunnel`() {
        assertEquals(
            "10.45.0.2:53",
            selectBootstrapDnsServer(
                listOf(address("127.0.0.1"), address("::1"), address("224.0.0.1"), address("10.45.0.2")),
            ),
        )
    }

    @Test
    fun `IP-only upstreams can start without network DNS`() {
        listOf("192.0.2.1", "2001:db8::1", "[2001:db8::1]", "::ffff:192.0.2.1").forEach { host ->
            assertFalse(host, requiresBootstrapDns(host))
        }
    }

    @Test
    fun `hostnames and unknown upstreams require a physical resolver`() {
        listOf(null, "", "lte.example.com", "192.0.2.999", "123", "2001:db8::invalid").forEach { host ->
            assertTrue(host, requiresBootstrapDns(host))
        }
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}
