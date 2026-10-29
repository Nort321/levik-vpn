package com.leviknet.vpn.vpn

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Live probes and the main balancer share one core. Existing outbound instances remain alive. */
internal class SmartAutoLiveConfig(
    val servers: List<TunnelServer>,
    val initialId: String,
    private val directory: File,
    private val service: ServiceRouteConfig?,
    val originals: List<TunnelServer> = servers,
) : AutoCloseable {
    private val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
    val controlPath = service?.controlPath ?: File(directory, "sa-$nonce-api.sock").path
    val ownsControl = service == null
    val candidates = servers.mapIndexed { index, server ->
        SmartRouteProbeConfig.Candidate(server.id, "levik-sa-live-$nonce-${index.toString().padStart(3, '0')}",
            File(directory, "sa-$nonce-live-$index.sock").path, server.syncHost())
    }
    @Volatile private var installedId = initialId
    @Volatile var dnsResolvers: List<String> = emptyList()
        private set
    val initial: SmartRouteProbeConfig.Candidate get() = candidates.first { it.id == installedId }
    fun installed(id: String) { require(candidates.any { it.id == id }); installedId = id }
    init { require((listOf(controlPath) + candidates.map { it.path }).all { it.toByteArray().size < 100 }) }

    fun apply(base: JsonObject): JsonObject {
        val outbounds = base.getValue("outbounds").jsonArray
        val originalTags = outbounds.mapNotNull { it.jsonObject["tag"] as? JsonPrimitive }.map { it.content }
        require(candidates.none { candidate -> originalTags.any { it.startsWith(candidate.tag) || candidate.tag.startsWith(it) } }) { "Automatic route tag conflicts with the profile" }
        val cloned = candidates.map { candidate ->
            val source = servers.first { it.id == candidate.id }
            val normalized = outbounds.first { it.jsonObject["tag"] == JsonPrimitive(source.tag) }.jsonObject
            JsonObject(normalized + ("tag" to JsonPrimitive(candidate.tag)))
        }
        val routing = base.getValue("routing").jsonObject
        val rules = routing.getValue("rules").jsonArray.map { value ->
            val rule = value.jsonObject
            if (rule["outboundTag"] == JsonPrimitive(servers.first { it.id == initialId }.tag) && rule["inboundTag"] != null)
                JsonObject(rule - "outboundTag" + ("balancerTag" to JsonPrimitive(ServiceRouteConfig.MAIN))) else rule
        }
        val balancer = buildJsonObject {
            put("tag", ServiceRouteConfig.MAIN)
            // Xray selectors are prefixes. Generated tags have fixed width and cannot overlap.
            put("selector", buildJsonArray { add(initial.tag) })
            put("strategy", buildJsonObject { put("type", "random") })
        }
        val dns = base["dns"] as? JsonObject
        dnsResolvers = (dns?.get("servers") as? JsonArray).orEmpty().mapNotNull { server ->
            (server as? JsonPrimitive)?.content ?: ((server as? JsonObject)?.get("address") as? JsonPrimitive)?.content
        }.filter { it != "localhost" && !it.contains("+local") }.distinct()
        val endpointNames = candidates.mapNotNull { it.endpointHost }.filter { ':' !in it && it.any(Char::isLetter) }.distinct()
        val effectiveDns = dns?.let { original -> JsonObject(original + buildMap {
            if (ownsControl) put("tag", JsonPrimitive(DNS))
            if (endpointNames.isNotEmpty()) put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", "localhost"); put("domains", JsonArray(endpointNames.map { JsonPrimitive("full:$it") }))
                    put("skipFallback", true); put("finalQuery", true)
                })
                (original["servers"] as? JsonArray).orEmpty().forEach(::add)
            })
        }) }
        return JsonObject(base + buildMap {
            put("api", buildJsonObject { put("tag", API); put("services", buildJsonArray { add("RoutingService"); add("StatsService") }) })
            put("stats", buildJsonObject {})
            val policy = base["policy"] as? JsonObject ?: JsonObject(emptyMap())
            val system = policy["system"] as? JsonObject ?: JsonObject(emptyMap())
            put("policy", JsonObject(policy + ("system" to JsonObject(system + mapOf(
                "statsInboundUplink" to JsonPrimitive(true), "statsInboundDownlink" to JsonPrimitive(true),
            )))))
            if (effectiveDns != null) put("dns", effectiveDns)
            put("outbounds", JsonArray(outbounds + cloned))
            put("inbounds", JsonArray(base.getValue("inbounds").jsonArray + buildList {
                if (ownsControl) add(buildJsonObject {
                    put("tag", API_IN); put("listen", "$controlPath,0600"); put("protocol", "dokodemo-door")
                    put("settings", buildJsonObject { put("address", "127.0.0.1") })
                })
                candidates.forEachIndexed { index, candidate -> add(buildJsonObject {
                    put("tag", "levik-sa-live-in-$index"); put("listen", "${candidate.path},0600")
                    put("protocol", "http"); put("settings", buildJsonObject {})
                }) }
            }))
            put("routing", JsonObject(routing + mapOf(
                "balancers" to JsonArray((routing["balancers"] as? JsonArray).orEmpty().filterNot { it.jsonObject["tag"] == JsonPrimitive(ServiceRouteConfig.MAIN) } + balancer),
                "rules" to buildJsonArray {
                    if (ownsControl) add(inboundRule(API_IN, API))
                    candidates.forEachIndexed { index, candidate -> add(inboundRule("levik-sa-live-in-$index", candidate.tag)) }
                    if (ownsControl) add(buildJsonObject { put("type", "field"); put("inboundTag", buildJsonArray { add(DNS) }); put("balancerTag", ServiceRouteConfig.MAIN) })
                    rules.forEach(::add)
                    add(buildJsonObject {
                        put("type", "field"); put("inboundTag", buildJsonArray { add("levik-tun-in") })
                        put("network", "tcp,udp"); put("balancerTag", ServiceRouteConfig.MAIN)
                    })
                },
            )))
        })
    }

    override fun close() {
        (candidates.map { it.path } + if (ownsControl) listOf(controlPath) else emptyList()).forEach { path ->
            File(path).delete(); File("$path.lock").delete()
        }
    }

    companion object {
        private const val API = "levik-sr-api"
        private const val API_IN = "levik-sr-api-in"
        private const val DNS = "levik-sa-live-dns"
        private fun inboundRule(inbound: String, outbound: String) = buildJsonObject {
            put("type", "field"); put("inboundTag", buildJsonArray { add(inbound) }); put("outboundTag", outbound)
        }
    }
}
