package com.leviknet.vpn.vpn

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartAutoStatsCodecTest {
    private val prefix = "inbound>>>levik-tun-in>>>traffic>>>"
    private fun variable(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var remaining = value
        do { val next = (remaining and 127).toInt(); remaining = remaining ushr 7; write(next or if (remaining != 0L) 128 else 0) } while (remaining != 0L)
    }.toByteArray()
    private fun stat(name: String, value: Long, omitZero: Boolean = false): ByteArray {
        val text = name.toByteArray()
        val body = byteArrayOf(10) + variable(text.size.toLong()) + text + if (value == 0L && omitZero) byteArrayOf() else byteArrayOf(16) + variable(value)
        return byteArrayOf(10) + variable(body.size.toLong()) + body
    }
    private fun frame(vararg values: ByteArray): ByteArray {
        val body = values.fold(byteArrayOf()) { all, value -> all + value }
        return ByteBuffer.allocate(body.size + 5).put(0).putInt(body.size).put(body).array()
    }

    @Test fun `only both exact user TUN counters count and private probe counters do not`() {
        val response = frame(stat("${prefix}uplink", 123), stat("${prefix}downlink", 456), stat("inbound>>>levik-sa-live-in-0>>>traffic>>>uplink", 999_999))
        assertEquals(SmartAutoTrafficCounters(123, 456), SmartAutoStatsCodec.response(response))
    }

    @Test fun `protobuf omitted zero value is valid only when its named counter exists`() {
        assertEquals(SmartAutoTrafficCounters(0, 0), SmartAutoStatsCodec.response(frame(stat("${prefix}uplink", 0, true), stat("${prefix}downlink", 0, true))))
        assertNull(SmartAutoStatsCodec.response(frame()))
        assertNull(SmartAutoStatsCodec.response(frame(stat("${prefix}uplink", 0, true))))
    }

    @Test fun `duplicate negative compressed and truncated frames are rejected`() {
        assertNull(SmartAutoStatsCodec.response(frame(stat("${prefix}uplink", 1), stat("${prefix}uplink", 1), stat("${prefix}downlink", 2))))
        assertNull(SmartAutoStatsCodec.response(frame(stat("${prefix}uplink", -1), stat("${prefix}downlink", 2))))
        val valid = frame(stat("${prefix}uplink", 1), stat("${prefix}downlink", 2))
        assertNull(SmartAutoStatsCodec.response(valid.copyOf().also { it[0] = 1 }))
        assertNull(SmartAutoStatsCodec.response(valid.copyOf(valid.size - 1)))
    }
}
