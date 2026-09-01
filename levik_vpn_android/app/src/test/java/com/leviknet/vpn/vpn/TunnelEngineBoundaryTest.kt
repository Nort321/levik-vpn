package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelEngineBoundaryTest {
    @Test
    fun `engine receives a borrowed tun and owns only an explicit duplicate`() {
        val handle = TrackingTunnelHandle(borrowedFd = 41)

        assertEquals(41, handle.borrowedFd)
        val duplicate = handle.duplicateForEngine()
        assertEquals(142, duplicate.fd)
        duplicate.close()

        assertTrue(handle.duplicateCreated)
        assertTrue(handle.duplicateClosed)
        assertFalse(handle.borrowedDescriptorClosed)
    }

    @Test
    fun `engine-owned descriptor can transfer ownership only once`() {
        val duplicate = TrackingOwnedDescriptor(142)

        assertEquals(142, duplicate.detach())
        assertThrows(IllegalStateException::class.java) { duplicate.detach() }
        duplicate.close()
        assertFalse(duplicate.closed)
    }

    @Test
    fun `tun plan rejects unsafe parameters before vpn builder`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunPlan(
                mtu = 0,
                addresses = listOf(TunAddress("172.30.0.2", 30)),
                dnsServers = listOf("1.1.1.1"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunPlan(
                mtu = 1_500,
                addresses = emptyList(),
                dnsServers = listOf("1.1.1.1"),
            )
        }
    }

    private class TrackingTunnelHandle(
        override val borrowedFd: Int,
    ) : TunnelFileDescriptorHandle {
        var duplicateCreated = false
        var duplicateClosed = false
        var borrowedDescriptorClosed = false

        override fun duplicateForEngine(): EngineOwnedTunnelFileDescriptor {
            duplicateCreated = true
            return object : EngineOwnedTunnelFileDescriptor {
                override val fd: Int = 142
                private var detached = false

                override fun detach(): Int {
                    check(!detached)
                    detached = true
                    return fd
                }

                override fun close() {
                    if (!detached) duplicateClosed = true
                }
            }
        }
    }

    private class TrackingOwnedDescriptor(
        override val fd: Int,
    ) : EngineOwnedTunnelFileDescriptor {
        private var detached = false
        var closed = false

        override fun detach(): Int {
            check(!detached)
            detached = true
            return fd
        }

        override fun close() {
            if (!detached) closed = true
        }
    }
}
