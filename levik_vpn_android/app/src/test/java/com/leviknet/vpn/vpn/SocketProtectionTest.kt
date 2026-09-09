package com.leviknet.vpn.vpn

import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketProtectionTest {
    @Test
    fun `invalid descriptors never reach Android`() {
        listOf(-1L, Int.MAX_VALUE.toLong() + 1).forEach { fd ->
            assertFalse(protectTunnelSocket(fd, false, { error("protect") }, { error("bind") }, {}))
        }
    }

    @Test
    fun `failed protection never binds or permits traffic`() {
        assertFalse(protectTunnelSocket(42, false, { false }, { error("bind") }, {}))
        assertFalse(protectTunnelSocket(42, false, { throw SecurityException() }, { error("bind") }, {}))
    }

    @Test
    fun `successful protection precedes binding`() {
        val calls = mutableListOf<String>()
        assertTrue(protectTunnelSocket(42, true, { calls += "protect:$it"; true },
            { calls += "bind:$it" }, { error("unexpected failure") }))
        assertEquals(listOf("protect:42", "bind:42"), calls)
    }

    @Test
    fun `connected UDP bind failure retains protection for unrestricted server`() {
        var protections = 0
        var reported = 0
        assertTrue(protectTunnelSocket(42, false, { protections++; true },
            { throw SocketException("Socket is connected") }, { reported++ }))
        assertEquals(2, protections)
        assertEquals(1, reported)
    }

    @Test
    fun `network restricted server fails closed when binding fails`() {
        assertFalse(protectTunnelSocket(42, true, { true }, { throw SocketException() }, {}))
    }

    @Test
    fun `fallback still fails closed if protection cannot be reasserted`() {
        var protections = 0
        assertFalse(protectTunnelSocket(42, false, { ++protections == 1 },
            { throw SocketException() }, {}))
    }
}
