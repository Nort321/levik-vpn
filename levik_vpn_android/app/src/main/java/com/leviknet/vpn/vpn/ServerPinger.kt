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
        return if (protocol == "hysteria" || protocol == "hysteria2" || protocol == "tuic" || protocol == "wireguard") {
            measureUdp(endpoint.first, endpoint.second)
        } else {
            measureTcp(endpoint.first, endpoint.second)
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
                if (protector?.protectDatagramSocket?.invoke(socket) == false) return null
                val target = InetSocketAddress(address, port)
                val probe = buildQuicProbePacket()
                val sendPacket = DatagramPacket(probe, probe.size, target)
                socket.send(sendPacket)
                val responseBuf = ByteArray(1500)
                val receivePacket = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(receivePacket)
            }
            (System.nanoTime() - startedAt) / 1_000_000
        } catch (_: Exception) {
            try {
                val fallbackStart = System.nanoTime()
                val address = InetAddress.getByName(host)
                if (address.isReachable(TIMEOUT_MS)) {
                    (System.nanoTime() - fallbackStart) / 1_000_000
                } else {
                    measureTcp(host, port)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildQuicProbePacket(): ByteArray {
        val packet = ByteArray(1200)
        packet[0] = 0xC0.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x01
        packet[5] = 0x08
        val dcid = ByteArray(8).also(random::nextBytes)
        System.arraycopy(dcid, 0, packet, 6, 8)
        packet[14] = 0x08
        val scid = ByteArray(8).also(random::nextBytes)
        System.arraycopy(scid, 0, packet, 15, 8)
        packet[23] = 0x00
        packet[24] = 0x44.toByte()
        packet[25] = 0x90.toByte()
        return packet
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
