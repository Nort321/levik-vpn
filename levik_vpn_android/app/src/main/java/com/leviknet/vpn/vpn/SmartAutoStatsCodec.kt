package com.leviknet.vpn.vpn

import java.nio.ByteBuffer

/** Small bounded protobuf codec for the pinned StatsService QueryStats contract. */
internal data class SmartAutoTrafficCounters(val uplink: Long, val downlink: Long)

internal object SmartAutoStatsCodec {
    private const val PREFIX = "inbound>>>levik-tun-in>>>traffic>>>"
    fun request(): ByteArray {
        val value = PREFIX.toByteArray(Charsets.UTF_8)
        val body = byteArrayOf(10, value.size.toByte()) + value
        return ByteBuffer.allocate(body.size + 5).put(0).putInt(body.size).put(body).array()
    }

    fun response(frame: ByteArray): SmartAutoTrafficCounters? = runCatching {
        require(frame.size in 5..4096 && frame[0] == 0.toByte())
        require(ByteBuffer.wrap(frame, 1, 4).int == frame.size - 5)
        val input = Reader(frame.copyOfRange(5, frame.size))
        val values = mutableMapOf<String, Long>()
        while (!input.done) {
            val key = input.varint().toInt()
            if (key != 10) { input.skip(key and 7); continue }
            val stat = Reader(input.bytes())
            var name: String? = null
            var value = 0L
            var valueSeen = false
            while (!stat.done) when (val field = stat.varint().toInt()) {
                10 -> { require(name == null); name = stat.bytes().toString(Charsets.UTF_8) }
                16 -> { require(!valueSeen); value = stat.varint(); valueSeen = true }
                else -> stat.skip(field and 7)
            }
            if (name == "${PREFIX}uplink" || name == "${PREFIX}downlink") {
                require(value >= 0 && name !in values)
                values[requireNotNull(name)] = value
            }
        }
        val up = values["${PREFIX}uplink"] ?: return@runCatching null
        val down = values["${PREFIX}downlink"] ?: return@runCatching null
        require(up <= Long.MAX_VALUE - down)
        SmartAutoTrafficCounters(up, down)
    }.getOrNull()

    private class Reader(private val input: ByteArray) {
        private var offset = 0
        val done: Boolean get() = offset == input.size
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(offset < input.size)
                val byte = input[offset++].toInt() and 255
                require(shift < 63 || byte <= 1)
                result = result or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return result
            }
            error("Invalid statistics value")
        }
        fun bytes(): ByteArray {
            val size = varint()
            require(size in 0L..4096L && offset + size <= input.size)
            return input.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
        }
        fun skip(wire: Int) { when (wire) {
            0 -> varint()
            2 -> bytes()
            1, 5 -> { val size = if (wire == 1) 8 else 4; require(offset + size <= input.size); offset += size }
            else -> error("Invalid statistics field")
        } }
    }
}
