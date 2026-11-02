package com.leviknet.vpn.vpn

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SharingTrafficCountersTest {
    private val tags = setOf("levik-tun-in", "levik-share-http", "levik-share-guest")

    @Test fun `counts each selected inbound once and ignores outbound and probe totals`() {
        val data = buildList {
            for (tag in tags) {
                add("inbound>>>$tag>>>traffic>>>uplink" to 10L)
                add("inbound>>>$tag>>>traffic>>>downlink" to 20L)
            }
            add("outbound>>>proxy>>>traffic>>>uplink" to 999L)
            add("inbound>>>probe>>>traffic>>>uplink" to 999L)
        }
        assertEquals(SmartAutoTrafficCounters(30, 60), SmartAutoStatsCodec.response(frame(data), tags))
    }

    @Test fun `missing direction or reset response never produces fabricated totals`() {
        val partial = listOf("inbound>>>levik-share-http>>>traffic>>>uplink" to 10L)
        assertNull(SmartAutoStatsCodec.response(frame(partial), tags))
        assertNull(SmartAutoStatsCodec.response(byteArrayOf(0, 0, 0, 0, 20), tags))
    }

    @Test fun `rejects duplicate and overflowing counters`() {
        val tag = "levik-share-http"
        val up = "inbound>>>$tag>>>traffic>>>uplink"
        val down = "inbound>>>$tag>>>traffic>>>downlink"
        assertNull(SmartAutoStatsCodec.response(frame(listOf(up to 1L, up to 1L, down to 2L)), setOf(tag)))
        assertNull(SmartAutoStatsCodec.response(frame(listOf(up to Long.MAX_VALUE, down to 1L)), setOf(tag)))
    }

    private fun frame(values: List<Pair<String, Long>>): ByteArray {
        fun varint(value: Long): ByteArray {
            var v = value
            val out = ByteArrayOutputStream()
            while (v >= 128) { out.write((v.toInt() and 127) or 128); v = v ushr 7 }
            out.write(v.toInt())
            return out.toByteArray()
        }
        val body = ByteArrayOutputStream()
        for ((name, value) in values) {
            val bytes = name.toByteArray()
            val stat = byteArrayOf(10) + varint(bytes.size.toLong()) + bytes + byteArrayOf(16) + varint(value)
            body.write(10); body.write(varint(stat.size.toLong())); body.write(stat)
        }
        val bytes = body.toByteArray()
        return ByteBuffer.allocate(bytes.size + 5).put(0).putInt(bytes.size).put(bytes).array()
    }
}
