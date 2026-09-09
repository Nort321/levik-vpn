package com.leviknet.vpn.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object ServerPinger {
    const val TIMEOUT_MS = 3_000
    private val socketProtector = AtomicReference<SocketProtector?>(null)
    private val random = SecureRandom()

    fun registerSocketProtector(owner: Long, protect: (Socket) -> Boolean) {
        socketProtector.set(SocketProtector(owner, protect, null))
    }

    fun registerSocketProtector(
        owner: Long,
        protectSocket: (Socket) -> Boolean,
        protectDatagramSocket: ((DatagramSocket) -> Boolean)?,
    ) {
        socketProtector.set(SocketProtector(owner, protectSocket, protectDatagramSocket))
    }

    fun unregisterSocketProtector(owner: Long) {
        socketProtector.updateAndGet { current -> current?.takeUnless { it.owner == owner } }
    }

    fun measure(server: TunnelServer): Long? =
        if (server.engine == TunnelEngineKind.XRAY) measure(server.outbound) else null

    fun measure(outbound: JsonObject): Long? {
        val endpoint = extractEndpoint(outbound) ?: return null
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        return when (protocol) {
            "hysteria", "hysteria2", "tuic" -> measureUdp(endpoint.first, endpoint.second)
            // WireGuard and obfuscated QUIC have no unauthenticated generic ping.
            "wireguard" -> null
            else -> measureTcp(endpoint.first, endpoint.second)
        }
    }

    private fun measureTcp(host: String, port: Int): Long? {
        val startedAt = System.nanoTime()
        return try {
            Socket().use { socket ->
                if (socketProtector.get()?.protectSocket?.invoke(socket) == false) return null
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            }
            (System.nanoTime() - startedAt) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }

    private fun measureUdp(host: String, port: Int): Long? {
        val startedAt = System.nanoTime()
        return try {
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val protector = socketProtector.get()
                if (protector != null && protector.protectDatagramSocket?.invoke(socket) != true) return null
                // A connected datagram socket accepts responses only from this endpoint.
                socket.connect(InetSocketAddress(address, port))
                val probe = buildQuicProbePacket()
                socket.send(DatagramPacket(probe, probe.size))
                val response = DatagramPacket(ByteArray(1500), 1500)
                socket.receive(response)
                if (!isQuicVersionNegotiation(probe, response.data.copyOf(response.length))) return null
            }
            (System.nanoTime() - startedAt) / 1_000_000
        } catch (_: Exception) {
            // TCP/ICMP success says nothing about this UDP service. A missing response
            // means unknown latency (including obfuscated servers), not a failed VPN.
            null
        }
    }

    internal fun buildQuicProbePacket(): ByteArray {
        val packet = ByteArray(1200).also(random::nextBytes)
        packet[0] = 0xC0.toByte()
        // RFC 9000 section 6.3: a reserved version solicits Version Negotiation.
        // A synthetic v1 Initial without valid AEAD is silently discarded by servers.
        for (index in 1..4) packet[index] = 0x0A
        packet[5] = 8
        packet[14] = 8
        return packet
    }

    internal fun isQuicVersionNegotiation(probe: ByteArray, response: ByteArray): Boolean {
        if (probe.size < 23 || response.size < 27 || (response.size - 23) % 4 != 0) return false
        if (response[0].toInt() and 0x80 == 0 || (1..4).any { response[it] != 0.toByte() }) return false
        if (response[5] != 8.toByte() || response[14] != 8.toByte()) return false
        if (!(0..7).all { response[6 + it] == probe[15 + it] && response[15 + it] == probe[6 + it] }) return false
        val versions = (23 until response.size step 4).map { offset -> response.copyOfRange(offset, offset + 4) }
        return versions.none { it.contentEquals(probe.copyOfRange(1, 5)) } &&
            versions.any { it.contentEquals(byteArrayOf(0, 0, 0, 1)) }
    }

    internal fun extractEndpoint(outbound: JsonObject): Pair<String, Int>? {
        val settings = outbound["settings"] as? JsonObject
        if (settings != null) {
            findEndpoint(settings, depth = 0)?.let { return it }
        }
        return findEndpoint(outbound, depth = 0)
    }

    private fun findEndpoint(element: JsonElement, depth: Int): Pair<String, Int>? {
        if (depth > MAX_ENDPOINT_DEPTH) return null
        if (element is JsonObject) {
            val address = (element["address"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: (element["host"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: (element["server"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            val port = (element["port"] as? JsonPrimitive)
                ?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
                ?.takeIf { it in 1..65535 }
            if (address != null && port != null) return address to port
        }
        return when (element) {
            is JsonObject -> element.values.firstNotNullOfOrNull {
                findEndpoint(it, depth + 1)
            }
            is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull {
                findEndpoint(it, depth + 1)
            }
            else -> null
        }
    }

    private data class SocketProtector(
        val owner: Long,
        val protectSocket: (Socket) -> Boolean,
        val protectDatagramSocket: ((DatagramSocket) -> Boolean)?,
    )

    private const val MAX_ENDPOINT_DEPTH = 6
}
