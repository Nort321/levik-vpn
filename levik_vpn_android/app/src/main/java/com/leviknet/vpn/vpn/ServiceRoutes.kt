package com.leviknet.vpn.vpn

import java.net.IDN
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** Optional, account-owned policy. Only an endpoint explicitly issued as ordinary may apply it. */
@Serializable
data class ServiceRouteProfile(
    val version: Int,
    val revision: Long,
    val fingerprint: String,
    val rules: List<ServiceRouteRule>,
    val exits: List<ServiceRouteExit>,
) {
    fun validate() {
        require(version == 1 && revision >= 0 && fingerprint.matches(Regex("^[a-f0-9]{64}$"))) {
            "Invalid service route policy"
        }
        require(rules.size <= 500 && exits.size <= 64)
        require(rules.map { it.id }.distinct().size == rules.size)
        require(exits.map { it.exitId }.distinct().size == exits.size)
        rules.forEach { rule ->
            require(rule.id.length in 1..160 && rule.id.none(Char::isISOControl))
            require(rule.label.length in 1..300 && rule.label.none(Char::isISOControl))
            require(rule.source in setOf("domain", "service", "category", "preset"))
            require(rule.domains.size in 1..500)
            rule.domains.forEach { domain ->
                require(domain.length in 3..1024 && domain.none(Char::isISOControl))
                require(domain.startsWith("domain:") || domain.startsWith("full:") || domain.startsWith("regexp:"))
            }
            rule.route.validate(fallback = false)
            rule.fallback.validate(fallback = true)
        }
        exits.forEach { exit ->
            require(exit.exitId.matches(Regex("^[A-Za-z0-9._:-]{1,100}$")))
            require(exit.name.length in 1..128 && exit.name.none(Char::isISOControl))
            require(exit.countryCode.matches(Regex("^[A-Z]{2}$")))
            require(exit.role == "ordinary" && exit.state in setOf("enabled", "draining", "disabled"))
            require(exit.endpoints.size in 1..32)
            exit.endpoints.forEach { endpoint ->
                require(endpoint.address.length in 1..253 && endpoint.address.none(Char::isISOControl))
                require(!endpoint.address.contains(Regex("[\\s/@?#\\\\]")))
                normalizedServiceHost(endpoint.address)
                require(endpoint.port in 1..65535 && endpoint.protocol.matches(Regex("^[a-z0-9-]{1,24}$")))
            }
        }
    }

    fun exitFor(server: TunnelServer): ServiceRouteExit? {
        if (server.engine !in setOf(TunnelEngineKind.XRAY, TunnelEngineKind.LEVIK_TUIC) || server.relayConfig != null || server.yandexConfig != null || server.gameConfig != null) return null
        val host = server.syncHost()?.let(::normalizedServiceHost) ?: return null
        val port = server.syncPort() ?: return null
        val protocol = if (server.engine == TunnelEngineKind.LEVIK_TUIC) "tuic" else (server.outbound["protocol"] as? JsonPrimitive)?.content?.lowercase() ?: return null
        val matches = exits.filter { exit ->
            exit.role == "ordinary" && exit.endpoints.any { endpoint ->
                normalizedServiceHost(endpoint.address) == host && endpoint.port == port && endpoint.protocol == protocol
            }
        }
        // An ambiguous registry entry never silently authorizes a shared LTE endpoint.
        return matches.singleOrNull()
    }

    fun appliesTo(profile: PreparedTunnelProfile, selected: TunnelServer): Boolean =
        rules.isNotEmpty() && !profile.gameOnly &&
            selected.engine == TunnelEngineKind.XRAY && exitFor(selected) != null
}

@Serializable
data class ServiceRouteRule(
    val id: String,
    val domains: List<String>,
    val route: ServiceRouteTarget,
    val fallback: ServiceRouteTarget,
    val source: String,
    val label: String,
)

@Serializable
data class ServiceRouteTarget(val kind: String, val countryCode: String? = null, val exitId: String? = null) {
    fun validate(fallback: Boolean) {
        require(kind in if (fallback) setOf("block", "direct", "main", "country") else setOf("default", "direct", "main", "country", "exit"))
        require((kind == "country") == (countryCode != null))
        require(countryCode == null || countryCode.matches(Regex("^[A-Z]{2}$")))
        require((kind == "exit") == (exitId != null))
        require(exitId == null || exitId.matches(Regex("^[A-Za-z0-9._:-]{1,100}$")))
    }
}

@Serializable
data class ServiceRouteExit(
    val exitId: String,
    val name: String,
    val countryCode: String,
    val state: String,
    val role: String,
    val endpoints: List<ServiceRouteEndpoint>,
)

@Serializable
data class ServiceRouteEndpoint(val address: String, val port: Int, val protocol: String)

internal fun normalizedServiceHost(value: String): String =
    value.trim().removeSurrounding("[", "]").removeSuffix(".").lowercase().let { host ->
        if (':' in host) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
    }

/** Pure selection policy: retains a healthy choice, excludes failed exits and never falls through. */
internal class ServiceRouteSelection(private val policy: ServiceRouteProfile, var mainExitId: String) {
    data class Decision(val target: String, val state: String, val exitId: String?)
    private val selected = mutableMapOf<String, Decision>()

    fun select(rule: ServiceRouteRule, alive: Set<String>): Decision {
        val previous = selected[rule.id]
        val primary = candidates(rule.route, alive)
        val chosen = if (primary.isNotEmpty()) {
            val retained = previous?.takeIf { it.state == "active" && it.exitId in primary }
            retained ?: Decision(primary.first(), "active", primary.first())
        } else when (rule.route.kind) {
            "default" -> Decision(BASE, "active", null)
            "direct" -> Decision(DIRECT, "active", null)
            else -> {
                val fallback = candidates(rule.fallback, alive)
                when {
                    fallback.isNotEmpty() -> previous?.takeIf { it.state == "fallback" && it.exitId in fallback }
                        ?: Decision(fallback.first(), "fallback", fallback.first())
                    rule.fallback.kind == "direct" -> Decision(DIRECT, "fallback", null)
                    else -> Decision(BLOCK, "blocked", null)
                }
            }
        }
        selected[rule.id] = chosen
        return chosen
    }

    private fun candidates(target: ServiceRouteTarget, alive: Set<String>): List<String> = policy.exits
        .filter { it.state == "enabled" && it.exitId in alive }
        .filter { exit -> when (target.kind) {
            "main" -> exit.exitId == mainExitId
            "country" -> exit.countryCode == target.countryCode
            "exit" -> exit.exitId == target.exitId
            else -> false
        } }
        .map { it.exitId }.sorted()

    companion object {
        const val DIRECT = "@direct"
        const val BLOCK = "@block"
        const val BASE = "@base"
    }
}
