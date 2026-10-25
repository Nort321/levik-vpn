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

/** A separate ordinary-only overlay. Strict routes start at the existing block outbound. */
class ServiceRouteConfig private constructor(
    val policy: ServiceRouteProfile,
    val mainExitId: String,
    val controlPath: String,
    val candidates: List<Candidate>,
    val allowMainFailover: Boolean,
) {
    data class Candidate(val exitId: String, val outboundTag: String, val probePath: String, val endpointHost: String)

    fun apply(base: JsonObject): JsonObject {
        val originalRules = base.getValue("routing").jsonObject.getValue("rules").jsonArray
        val baseRules = originalRules.map { value ->
            val rule = value.jsonObject
            val tags = rule["inboundTag"] as? JsonArray
            if (tags?.any { it == JsonPrimitive(TUN) } == true) JsonObject(rule + ("inboundTag" to JsonArray(tags + JsonPrimitive(BASE_IN)))) else rule
        }
        val firstDns = base.getValue("dns").jsonObject.getValue("servers").jsonArray.firstOrNull() as? JsonPrimitive
        val resolver = requireNotNull(firstDns?.content) { "Service route DNS is unavailable" }
        require(!resolver.contains("+local") && resolver != "localhost") { "Local DNS does not support service routing" }
        val dns = JsonObject(base.getValue("dns").jsonObject + mapOf(
            "tag" to JsonPrimitive(BASE_DNS),
            "disableCache" to JsonPrimitive(true),
            "disableFallbackIfMatch" to JsonPrimitive(true),
            "servers" to buildJsonArray {
                val bootstrapNames = candidates.map { it.endpointHost }.filter { ':' !in it && it.any(Char::isLetter) }.distinct()
                if (bootstrapNames.isNotEmpty()) add(buildJsonObject {
                    // libXray's OS resolver is bound to the selected physical network before core startup.
                    put("address", "localhost")
                    put("domains", JsonArray(bootstrapNames.map { JsonPrimitive("full:$it") }))
                    put("skipFallback", true)
                    put("finalQuery", true)
                })
                policy.rules.forEachIndexed { index, rule ->
                    add(buildJsonObject {
                        put("address", resolver)
                        put("domains", JsonArray(rule.domains.map(::JsonPrimitive)))
                        put("tag", dnsTag(index))
                        put("skipFallback", true)
                        put("finalQuery", true)
                    })
                }
                base.getValue("dns").jsonObject.getValue("servers").jsonArray.forEach(::add)
            },
        ))
        return JsonObject(base + mapOf(
            "api" to buildJsonObject {
                put("tag", API)
                put("services", buildJsonArray { add("RoutingService") })
            },
            "dns" to dns,
            "inbounds" to JsonArray(base.getValue("inbounds").jsonArray + buildList {
                add(buildJsonObject {
                    put("tag", API_IN)
                    put("listen", "$controlPath,0600")
                    put("protocol", "dokodemo-door")
                    put("settings", buildJsonObject { put("address", "127.0.0.1") })
                })
                candidates.forEachIndexed { index, candidate ->
                    add(buildJsonObject {
                        put("tag", probeTag(index))
                        put("listen", "${candidate.probePath},0600")
                        put("protocol", "http")
                        put("settings", buildJsonObject {})
                    })
                }
            }),
            "outbounds" to JsonArray(base.getValue("outbounds").jsonArray + listOf(
                buildJsonObject {
                    put("tag", BASE_OUT)
                    put("protocol", "loopback")
                    put("settings", buildJsonObject { put("inboundTag", BASE_IN) })
                },
                buildJsonObject { put("tag", DNS_OUT); put("protocol", "dns"); put("settings", buildJsonObject {}) },
            )),
            "routing" to JsonObject(base.getValue("routing").jsonObject + mapOf(
                "balancers" to buildJsonArray {
                    val selectedTag = candidates.first { it.exitId == mainExitId }.outboundTag
                    val initialMain = if (policy.exits.first { it.exitId == mainExitId }.state == "enabled") selectedTag else BLOCK
                    add(balancer(MAIN, initialMain))
                    policy.rules.forEachIndexed { index, rule ->
                        add(balancer(routeTag(index), when (rule.route.kind) {
                            "direct" -> DIRECT
                            "default" -> BASE_OUT
                            "main" -> initialMain
                            else -> BLOCK
                        }))
                    }
                },
                "rules" to buildJsonArray {
                    add(inboundRule(API_IN, API))
                    candidates.forEachIndexed { index, candidate -> add(inboundRule(probeTag(index), candidate.outboundTag)) }
                    policy.rules.forEachIndexed { index, _ -> add(inboundBalancerRule(dnsTag(index), routeTag(index))) }
                    add(inboundRule(BASE_DNS, BASE_OUT))
                    // Preserve the ordinary IPv6 policy ahead of domain matching.
                    baseRules.firstOrNull()?.let(::add)
                    add(buildJsonObject {
                        // Reentered internal DNS already passed the resolver; hijacking it again recurses.
                        put("type", "field"); put("inboundTag", buildJsonArray { add(TUN) })
                        put("port", "53"); put("outboundTag", DNS_OUT)
                    })
                    policy.rules.forEachIndexed { index, rule ->
                        add(buildJsonObject {
                            put("type", "field"); put("inboundTag", buildJsonArray { add(TUN) })
                            put("domain", JsonArray(rule.domains.map(::JsonPrimitive)))
                            put("balancerTag", routeTag(index))
                        })
                    }
                    baseRules.drop(1).forEach { value ->
                        val rule = value.jsonObject
                        val target = rule["outboundTag"]
                        val selectedTag = candidates.first { it.exitId == mainExitId }.outboundTag
                        if (target == JsonPrimitive(selectedTag)) add(JsonObject(rule - "outboundTag" + ("balancerTag" to JsonPrimitive(MAIN)))) else add(rule)
                    }
                    add(buildJsonObject {
                        put("type", "field"); put("inboundTag", buildJsonArray { add(TUN); add(BASE_IN) })
                        put("network", "tcp,udp"); put("balancerTag", MAIN)
                    })
                },
            )),
        ))
    }

    fun close() {
        // Exact session-owned paths under the app's private files directory only.
        (listOf(controlPath) + candidates.map { it.probePath }).forEach { path ->
            File(path).delete()
            File("$path.lock").delete()
        }
    }

    internal fun tagFor(decision: ServiceRouteSelection.Decision): String = when (decision.target) {
        ServiceRouteSelection.DIRECT -> DIRECT
        ServiceRouteSelection.BLOCK -> BLOCK
        ServiceRouteSelection.BASE -> BASE_OUT
        else -> candidates.firstOrNull { it.exitId == decision.target }?.outboundTag ?: BLOCK
    }

    companion object {
        const val MAIN = "levik-sr-main"
        private const val API = "levik-sr-api"
        private const val API_IN = "levik-sr-api-in"
        private const val TUN = "levik-tun-in"
        private const val BASE_IN = "levik-sr-base-in"
        private const val BASE_OUT = "levik-sr-base-out"
        private const val BASE_DNS = "levik-sr-base-dns"
        private const val DNS_OUT = "levik-sr-dns-out"
        const val BLOCK = "levik-block"
        const val DIRECT = "levik-direct"
        fun routeTag(index: Int) = "levik-sr-route-$index"
        private fun dnsTag(index: Int) = "levik-sr-dns-$index"
        private fun probeTag(index: Int) = "levik-sr-probe-$index"

        fun prepare(profile: PreparedTunnelProfile, selected: TunnelServer, privateDirectory: File, allowMainFailover: Boolean = false): ServiceRouteConfig? {
            val policy = profile.serviceRoutes ?: return null
            if (!policy.appliesTo(profile, selected)) return null
            policy.validate()
            require(profile.servers.none { it.tag.startsWith("levik-sr-") }) { "Service route tag conflicts with the source" }
            val main = requireNotNull(policy.exitFor(selected)).exitId
            val needed = policy.exits.filter { exit ->
                allowMainFailover || exit.exitId == main || policy.rules.any { rule -> listOf(rule.route, rule.fallback).any { target ->
                    target.kind == "country" && target.countryCode == exit.countryCode || target.kind == "exit" && target.exitId == exit.exitId
                } }
            }.map { it.exitId }.toSet()
            val nonce = UUID.randomUUID().toString().take(8)
            val directory = privateDirectory.canonicalFile
            val candidates = (listOf(selected) + profile.servers.filterNot { it.id == selected.id })
                .mapNotNull { server -> policy.exitFor(server)?.takeIf { (it.state == "enabled" || it.exitId == main) && it.exitId in needed }?.let { it.exitId to server } }
                .distinctBy { it.first }
                .mapIndexed { index, (exitId, server) -> Candidate(exitId, server.tag, File(directory, "sr-$nonce-p$index.sock").path, requireNotNull(server.syncHost())) }
            val controlPath = File(directory, "sr-$nonce-api.sock").path
            require(controlPath.toByteArray().size < 100 && candidates.all { it.probePath.toByteArray().size < 100 }) { "Service route socket path is too long" }
            return ServiceRouteConfig(policy, main, controlPath, candidates, allowMainFailover)
        }

        private fun balancer(tag: String, initial: String = BLOCK) = buildJsonObject {
            put("tag", tag)
            put("selector", buildJsonArray { add(initial) })
            put("strategy", buildJsonObject { put("type", "random") })
        }
        private fun inboundRule(inbound: String, outbound: String) = buildJsonObject {
            put("type", "field"); put("inboundTag", buildJsonArray { add(inbound) }); put("outboundTag", outbound)
        }
        private fun inboundBalancerRule(inbound: String, balancer: String) = buildJsonObject {
            put("type", "field"); put("inboundTag", buildJsonArray { add(inbound) }); put("balancerTag", balancer)
        }
    }
}
