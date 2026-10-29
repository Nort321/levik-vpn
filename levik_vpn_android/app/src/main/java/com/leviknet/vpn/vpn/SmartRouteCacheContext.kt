package com.leviknet.vpn.vpn

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** In-memory digest only: credentials and settings never become a log or persisted cache key. */
internal object SmartRouteCacheContext {
    fun fingerprint(server: TunnelServer, settings: List<String>): String {
        val parts = listOf(server.engine.name, server.outbound.toString(), server.tuicConfig?.let {
            Json.encodeToString(TuicServerConfig.serializer(), it)
        }.orEmpty()) + settings
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
