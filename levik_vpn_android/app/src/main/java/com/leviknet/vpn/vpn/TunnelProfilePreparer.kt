package com.leviknet.vpn.vpn

import java.util.Locale
import kotlinx.serialization.json.JsonObject

class TunnelProfilePreparer(
    private val xrayRuntime: XrayRuntime,
    /** True only where the TUIC sidecar is packaged (Direct). */
    private val includeTuic: Boolean = false,
) {
    fun prepare(profile: TunnelProfile): PreparedTunnelProfile {
        val converted = xrayRuntime.convertProfile(profile)
        val relayServers = profile.bootstrap?.let { bootstrap ->
            prepareRelayServers(bootstrap, profile.routing)
        }.orEmpty()
        val yandexServers = profile.yandexBootstrap?.let { bootstrap ->
            listOf(TunnelServer(
                id = "yandex:document",
                tag = "yandex:document",
                name = "Через Яндекс",
                countryCode = "XX",
                outbound = JsonObject(emptyMap()),
                engine = TunnelEngineKind.LEVIK_YANDEX,
                category = TunnelServerCategory.MOBILE_ALLOWLIST,
                yandexConfig = YandexServerConfig(bootstrap, profile.routing),
            ))
        }.orEmpty()
        val tuicServers = profile.source
            ?.takeIf { includeTuic }
            ?.let { source -> TuicLinkParser.parse(source.content) }
            .orEmpty()
        val servers = converted.servers + tuicServers + relayServers + yandexServers

        require(servers.isNotEmpty()) { "Tunnel profile has no servers" }
        require(servers.size <= MAX_SERVERS) { "Tunnel profile has too many servers" }
        require(servers.map(TunnelServer::id).distinct().size == servers.size) {
            "Tunnel profile contains duplicate server identifiers"
        }
        require(servers.map(TunnelServer::tag).distinct().size == servers.size) {
            "Tunnel profile contains duplicate server tags"
        }

        return converted.copy(
            relayCredentialExpiresAt = profile.bootstrap?.expiresAt,
            servers = servers,
        )
    }

    internal fun prepareRelayServers(
        bootstrap: RelayBootstrap,
        routing: TunnelRouting?,
    ): List<TunnelServer> = bootstrap.nodes.map { node ->
        val stableId = "relay:${node.id}"
        TunnelServer(
            id = stableId,
            tag = stableId,
            name = node.displayName,
            countryCode = node.countryCode.uppercase(Locale.ROOT),
            outbound = JsonObject(emptyMap()),
            engine = TunnelEngineKind.LEVIK_RELAY,
            category = TunnelServerCategory.MOBILE_ALLOWLIST,
            networkRequirement = TunnelNetworkRequirement.ANY,
            relayConfig = RelayServerConfig(
                bootstrap = bootstrap,
                node = node,
                routing = routing,
            ),
        )
    }

    private companion object {
        const val MAX_SERVERS = 200
    }
}
