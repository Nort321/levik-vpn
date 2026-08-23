package com.leviknet.vpn.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object ServerPinger {
    const val TIMEOUT_MS = 3_000
    private val socketProtector = AtomicReference<SocketProtector?>(null)

    fun registerSocketProtector(owner: Long, protect: (Socket) -> Boolean) {
        socketProtector.set(SocketProtector(owner, protect))
    }

    fun unregisterSocketProtector(owner: Long) {
        socketProtector.updateAndGet { current -> current?.takeUnless { it.owner == owner } }
    }

    fun measure(outbound: JsonObject): Long? {
        val endpoint = extractEndpoint(outbound) ?: return null
        val startedAt = System.nanoTime()
        return try {
            Socket().use { socket ->
                if (socketProtector.get()?.protect?.invoke(socket) == false) return null
                socket.connect(InetSocketAddress(endpoint.first, endpoint.second), TIMEOUT_MS)
            }
            (System.nanoTime() - startedAt) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractEndpoint(outbound: JsonObject): Pair<String, Int>? {
        val settings = outbound["settings"] as? JsonObject ?: return null
        return findEndpoint(settings, depth = 0)
    }

    private fun findEndpoint(element: JsonElement, depth: Int): Pair<String, Int>? {
        if (depth > MAX_ENDPOINT_DEPTH) return null
        if (element is JsonObject) {
            val address = (element["address"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
            val port = (element["port"] as? JsonPrimitive)
                ?.intOrNull
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
        val protect: (Socket) -> Boolean,
    )

    private const val MAX_ENDPOINT_DEPTH = 6
}
