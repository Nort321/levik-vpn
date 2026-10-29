package com.leviknet.vpn.vpn

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/** No TUN and no direct fallback: each app-private inbound can only reach its assigned VPN outbound. */
internal class SmartRouteProbeConfig(servers: List<TunnelServer>, privateDirectory: File) : AutoCloseable {
    data class Candidate(val id: String, val tag: String, val path: String, val endpointHost: String? = null)
    private val nonce = UUID.randomUUID().toString().take(8)
    val candidates = servers.mapIndexed { index, server ->
        Candidate(server.id, server.tag, File(privateDirectory.canonicalFile, "sa-$nonce-$index.sock").path, server.syncHost())
    }
    init { require(candidates.all { it.path.toByteArray().size < 100 }) { "Probe socket path is too long" } }

    fun apply(base: JsonObject): JsonObject {
        val endpointNames = candidates.mapNotNull { it.endpointHost }.filter { ':' !in it && it.any(Char::isLetter) }.distinct()
        val dns = base["dns"] as? JsonObject
        val withBootstrap = if (dns != null && endpointNames.isNotEmpty()) {
            val servers = buildJsonArray {
                // Only proxy endpoint names use the protected physical resolver installed by LibXray.setDNS.
                // A fragment dialer can bootstrap its endpoint without routing DNS back into itself.
                add(buildJsonObject {
                    put("address", "localhost")
                    put("domains", JsonArray(endpointNames.map { JsonPrimitive("full:$it") }))
                    put("skipFallback", true)
                    put("finalQuery", true)
                })
                (dns["servers"] as? JsonArray).orEmpty().forEach(::add)
            }
            JsonObject(base + ("dns" to JsonObject(dns + ("servers" to servers))))
        } else base
        return JsonObject(withBootstrap - "env" - "api" + mapOf(
        "inbounds" to buildJsonArray {
            candidates.forEachIndexed { index, candidate -> add(buildJsonObject {
                put("tag", "levik-sa-$index")
                put("listen", "${candidate.path},0600")
                put("protocol", "http")
                put("settings", buildJsonObject {})
            }) }
        },
        "routing" to buildJsonObject {
            put("domainStrategy", "AsIs")
            put("rules", buildJsonArray {
                candidates.forEachIndexed { index, candidate -> add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", buildJsonArray { add("levik-sa-$index") })
                    put("outboundTag", candidate.tag)
                }) }
                add(buildJsonObject { put("type", "field"); put("network", "tcp,udp"); put("outboundTag", "levik-block") })
            })
        },
        ))
    }

    override fun close() {
        candidates.forEach { candidate -> File(candidate.path).delete(); File("${candidate.path}.lock").delete() }
    }
}
