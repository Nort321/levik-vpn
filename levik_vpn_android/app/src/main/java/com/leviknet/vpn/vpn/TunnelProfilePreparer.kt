package com.leviknet.vpn.vpn

import java.util.Locale
import kotlinx.serialization.json.JsonObject

class TunnelProfilePreparer(
    private val xrayRuntime: XrayRuntime,
    /** True only where the TUIC sidecar is packaged (Direct). */
    private val includeTuic: Boolean = false,
    /** True only where the Game sidecar is packaged (Direct). */
    private val includeGame: Boolean = false,
    private val includeDnstt: Boolean = false,
) {
    fun prepare(profile: TunnelProfile): PreparedTunnelProfile {
        val converted = xrayRuntime.convertProfile(profile.copy(source = profile.source?.let(DnsttLinkParser::withoutLinks)))
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
        val telemostServers = profile.telemostBootstrap?.let { bootstrap ->
            listOf(TunnelServer(
                id = "yandex:telemost", tag = "yandex:telemost", name = "Через Яндекс", countryCode = "XX",
                outbound = JsonObject(emptyMap()), engine = TunnelEngineKind.LEVIK_YANDEX,
                category = TunnelServerCategory.MOBILE_ALLOWLIST,
                yandexConfig = YandexServerConfig(routing = profile.routing, telemost = bootstrap),
            ))
        }.orEmpty()
        val tuicServers = profile.source
            ?.takeIf { includeTuic }
            ?.let { source -> TuicLinkParser.parse(source.content) }
            .orEmpty()
        // Remnawave's stub links (expired, no ordinary servers on «Только Game»…) are never servers.
        val (placeholders, xrayServers) = converted.servers.partition { it.isSubscriptionPlaceholder() }
        // Game node links never become ordinary servers; Direct makes them one Levik Game server.
        val game = GameProfile.split(xrayServers + tuicServers, profile.game, includeGame)
        val dnsServers = profile.source?.takeIf { includeDnstt }?.let { DnsttLinkParser.parse(it.content) }.orEmpty()
        val servers = game.ordinary + listOfNotNull(game.game) + relayServers + yandexServers + telemostServers + dnsServers

        if (servers.isEmpty() && placeholders.isNotEmpty()) {
            throw SubscriptionNoticeException(subscriptionNotice(placeholders.first().name))
        }
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
            gameOnly = game.game?.gameConfig?.only == true,
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
