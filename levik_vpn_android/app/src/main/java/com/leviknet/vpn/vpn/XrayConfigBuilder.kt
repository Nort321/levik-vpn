package com.leviknet.vpn.vpn

import com.leviknet.vpn.data.RoutingPreset
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class XrayConfigBuilder(
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun buildRelayProxy(
        profile: PreparedTunnelProfile,
        tunFileDescriptor: Int,
        proxy: LocalProxyEndpoint,
        primaryDnsIp: String,
        secondaryDnsIp: String,
        lteDirectCidrs: List<String>,
        lteDirectDomains: List<String>,
        routingPreset: RoutingPreset = RoutingPreset.BYPASS_RU,
        customDirectDomains: Set<String> = emptySet(),
        customProxyDomains: Set<String> = emptySet(),
        resolveTargets: Boolean = false,
    ): String {
        val proxyOutbound = buildJsonObject {
            put("tag", RELAY_PROXY_TAG)
            put("protocol", "socks")
            if (resolveTargets) put("targetStrategy", "ForceIPv4")
            put("settings", buildJsonObject {
                put("address", proxy.address)
                put("port", proxy.port)
                put("user", proxy.username)
                put("pass", proxy.password)
            })
        }
        val syntheticProfile = profile.copy(
            servers = listOf(
                TunnelServer(
                    id = RELAY_PROXY_TAG,
                    tag = RELAY_PROXY_TAG,
                    name = RELAY_PROXY_TAG,
                    countryCode = "XX",
                    outbound = proxyOutbound,
                    engine = TunnelEngineKind.XRAY,
                    category = TunnelServerCategory.MOBILE_ALLOWLIST,
                ),
            ),
            directCidrs = emptyList(),
            directDomains = emptyList(),
            proxyDomains = emptyList(),
        )
        return build(
            profile = syntheticProfile,
            selectedServerId = RELAY_PROXY_TAG,
            tunFileDescriptor = tunFileDescriptor,
            routingPreset = routingPreset,
            customDirectDomains = customDirectDomains,
            customProxyDomains = customProxyDomains,
            primaryDnsIp = primaryDnsIp,
            secondaryDnsIp = secondaryDnsIp,
            effectiveRoutingProfile = EffectiveRoutingProfile.LTE,
            lteDirectCidrs = lteDirectCidrs,
            lteDirectDomains = lteDirectDomains,
            preserveTargetAddress = resolveTargets,
        )
    }

    /**
     * Replaces the selected TUIC server with a loopback SOCKS5 outbound to the running
     * sidecar, so the regular Xray configuration (routing, DNS, split tunnelling) applies.
     */
    fun withTuicProxy(
        profile: PreparedTunnelProfile,
        tuicServerId: String,
        proxy: LocalProxyEndpoint,
    ): PreparedTunnelProfile {
        val tuicServer = profile.servers.firstOrNull { it.id == tuicServerId && it.engine == TunnelEngineKind.LEVIK_TUIC }
            ?: throw IllegalArgumentException("Selected TUIC server is unavailable")
        val proxyOutbound = buildJsonObject {
            put("tag", tuicServer.tag)
            put("protocol", "socks")
            put("settings", buildJsonObject {
                put("address", proxy.address)
                put("port", proxy.port)
                put("user", proxy.username)
                put("pass", proxy.password)
            })
        }
        val proxied = tuicServer.copy(engine = TunnelEngineKind.XRAY, outbound = proxyOutbound, tuicConfig = null)
        return profile.copy(servers = listOf(proxied) + profile.servers.filter { it.engine == TunnelEngineKind.XRAY })
    }

    fun build(
        profile: PreparedTunnelProfile,
        selectedServerId: String,
        tunFileDescriptor: Int,
        routingPreset: RoutingPreset = RoutingPreset.BYPASS_RU,
        bypassRussianTraffic: Boolean = (routingPreset == RoutingPreset.BYPASS_RU),
        russianDirectCidrs: List<String> = emptyList(),
        primaryDnsIp: String = PRIMARY_DNS_IP,
        secondaryDnsIp: String = SECONDARY_DNS_IP,
        dohEndpoint: String? = null,
        antiDpiEnabled: Boolean = false,
        antiDpiPackets: String = DEFAULT_ANTI_DPI_PACKETS,
        antiDpiLength: String = DEFAULT_ANTI_DPI_LENGTH,
        antiDpiInterval: String = DEFAULT_ANTI_DPI_INTERVAL,
        customDirectDomains: Set<String> = emptySet(),
        customProxyDomains: Set<String> = emptySet(),
        effectiveRoutingProfile: EffectiveRoutingProfile = EffectiveRoutingProfile.USER_SELECTED,
        lteDirectCidrs: List<String> = emptyList(),
        lteDirectDomains: List<String> = emptyList(),
        preserveTargetAddress: Boolean = false,
        xhttpMuxHosts: Set<String> = emptySet(),
        serviceRouteSession: ServiceRouteConfig? = null,
    ): String {
        require(tunFileDescriptor >= 0) { "Invalid TUN file descriptor" }
        profile.subscriptionExpiresAt?.let { value ->
            require(Instant.parse(value).isAfter(clock.instant())) {
                "Subscription has expired"
            }
        }
        val selected = profile.servers.firstOrNull { it.id == selectedServerId }
            ?: throw IllegalArgumentException("Selected server is unavailable")
        require(selected.engine == TunnelEngineKind.XRAY) {
            "Selected server does not use the Xray engine"
        }
        val xrayServers = profile.servers.filter { it.engine == TunnelEngineKind.XRAY }
        validateServers(xrayServers)

        val orderedServers = (listOf(selected) + xrayServers.filterNot { it.id == selected.id })
            .map { server ->
                var repairedOutbound = enableXhttpMux(RealityRepair.repair(server.outbound), xhttpMuxHosts)
                if (antiDpiEnabled) {
                    repairedOutbound = injectFragmentDialer(repairedOutbound)
                }
                server.copy(outbound = repairedOutbound)
            }
        orderedServers.forEach(::requireRealityServerNames)

        // Mobile bypass keeps its curated rules; Global and Anti-Block remain user choices.
        val isLte = effectiveRoutingProfile == EffectiveRoutingProfile.LTE &&
            routingPreset == RoutingPreset.BYPASS_RU
        if (isLte) {
            require(lteDirectCidrs.isNotEmpty()) { "LTE CIDR routing data is unavailable" }
            require(lteDirectDomains.isNotEmpty()) { "LTE domain routing data is unavailable" }
        }
        val isBypassRu = !isLte &&
            (routingPreset == RoutingPreset.BYPASS_RU || bypassRussianTraffic)
        val isBlockedOnly = !isLte && routingPreset == RoutingPreset.BLOCKED_ONLY

        val directCidrs = if (isLte) {
            lteDirectCidrs.distinct()
        } else {
            (
                BUILT_IN_DIRECT_CIDRS +
                    profile.directCidrs +
                    if (isBypassRu) russianDirectCidrs else emptyList()
                ).distinct()
        }

        val directDomains = buildList {
            if (isLte) {
                addAll(lteDirectDomains)
            } else {
                addAll(profile.directDomains)
                if (isBypassRu) {
                    addAll(RUSSIAN_DOMAINS)
                }
                customDirectDomains.forEach { domain ->
                    val clean = domain.trim().lowercase()
                    if (clean.isNotBlank()) {
                        add(if (clean.startsWith("domain:") || clean.startsWith("full:") || clean.startsWith("geosite:")) clean else "domain:$clean")
                    }
                }
            }
        }

        val proxyDomains = buildList {
            if (!isLte) {
                addAll(profile.proxyDomains)
                if (isBlockedOnly) {
                    addAll(POPULAR_BLOCKED_DOMAINS)
                }
                customProxyDomains.forEach { domain ->
                    val clean = domain.trim().lowercase()
                    if (clean.isNotBlank()) {
                        add(if (clean.startsWith("domain:") || clean.startsWith("full:") || clean.startsWith("geosite:")) clean else "domain:$clean")
                    }
                }
            }
        }

        val additionalOutbounds = buildList {
            if (antiDpiEnabled) {
                val sanitizedPackets = sanitizeAntiDpiParam(antiDpiPackets, DEFAULT_ANTI_DPI_PACKETS)
                val sanitizedLength = sanitizeAntiDpiParam(antiDpiLength, DEFAULT_ANTI_DPI_LENGTH)
                val sanitizedInterval = sanitizeAntiDpiParam(antiDpiInterval, DEFAULT_ANTI_DPI_INTERVAL)

                add(buildJsonObject {
                    put("tag", FRAGMENT_TAG)
                    put("protocol", "freedom")
                    put("settings", buildJsonObject {
                        put("domainStrategy", "UseIPv4")
                        put("fragment", buildJsonObject {
                            put("packets", sanitizedPackets)
                            put("length", sanitizedLength)
                            put("interval", sanitizedInterval)
                        })
                    })
                    put("streamSettings", buildJsonObject {
                        put("sockopt", buildJsonObject {
                            put("mark", 255)
                        })
                    })
                })
            }
            add(buildJsonObject {
                put("tag", DIRECT_TAG)
                put("protocol", "freedom")
                put("settings", buildJsonObject {
                    put("domainStrategy", "UseIPv4")
                })
            })
            add(buildJsonObject {
                put("tag", BLOCK_TAG)
                put("protocol", "blackhole")
                put("settings", buildJsonObject {})
            })
        }

        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", "none")
            })
            put("env", buildJsonObject {
                put(TUN_FD_ENV, tunFileDescriptor.toString())
            })
            put("dns", buildJsonObject {
                put("queryStrategy", "UseIPv4")
                put("servers", buildJsonArray {
                    if (!dohEndpoint.isNullOrBlank()) {
                        add(JsonPrimitive(dohEndpoint.trim()))
                    }
                    add(JsonPrimitive(primaryDnsIp))
                    add(JsonPrimitive(secondaryDnsIp))
                })
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", TUN_INBOUND_TAG)
                    put("protocol", "tun")
                    put("port", 0)
                    put("settings", buildJsonObject {
                        put("name", "levik0")
                        put("mtu", TUN_MTU)
                    })
                    put("sniffing", buildJsonObject {
                        put("enabled", true)
                        // Managed IPv4 SOCKS receives the application's resolved
                        // destination. An outer TLS/QUIC name (including ECH)
                        // may be used for routing without changing that address.
                        if (preserveTargetAddress) put("routeOnly", true)
                        put("destOverride", buildJsonArray {
                            add(JsonPrimitive("http"))
                            add(JsonPrimitive("tls"))
                            add(JsonPrimitive("quic"))
                        })
                    })
                })
            })
            put("outbounds", JsonArray(
                orderedServers.map(TunnelServer::outbound) + additionalOutbounds
            ))
            put("routing", buildJsonObject {
                put("domainStrategy", if (!preserveTargetAddress && (isLte || isBypassRu || isBlockedOnly || directDomains.isNotEmpty())) "IPIfNonMatch" else "AsIs")
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray {
                            add(JsonPrimitive(TUN_INBOUND_TAG))
                        })
                        put("ip", buildJsonArray {
                            add(JsonPrimitive(IPV6_DEFAULT_ROUTE))
                        })
                        put("outboundTag", BLOCK_TAG)
                    })
                    if (proxyDomains.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "field")
                            put("inboundTag", buildJsonArray {
                                add(JsonPrimitive(TUN_INBOUND_TAG))
                            })
                            put("domain", JsonArray(proxyDomains.map(::JsonPrimitive)))
                            put("outboundTag", selected.tag)
                        })
                    }
                    if (directDomains.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "field")
                            put("inboundTag", buildJsonArray {
                                add(JsonPrimitive(TUN_INBOUND_TAG))
                            })
                            put("domain", JsonArray(directDomains.map(::JsonPrimitive)))
                            put("outboundTag", DIRECT_TAG)
                        })
                    }
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add(JsonPrimitive(TUN_INBOUND_TAG)) })
                        put("ip", JsonArray(directCidrs.map(::JsonPrimitive)))
                        put("outboundTag", DIRECT_TAG)
                    })
                    if (isBlockedOnly) {
                        // In Blocked Only mode, DNS queries route through proxy so they are not hijacked by local ISPs
                        add(buildJsonObject {
                            put("type", "field")
                            put("inboundTag", buildJsonArray { add(JsonPrimitive(TUN_INBOUND_TAG)) })
                            put("port", "53")
                            put("network", "udp,tcp")
                            put("outboundTag", selected.tag)
                        })
                        // In Blocked Only mode, unrouted traffic defaults to direct
                        add(buildJsonObject {
                            put("type", "field")
                            put("inboundTag", buildJsonArray { add(JsonPrimitive(TUN_INBOUND_TAG)) })
                            put("network", "tcp,udp")
                            put("outboundTag", DIRECT_TAG)
                        })
                    }
                })
            })
        }
        val servicePolicy = profile.serviceRoutes?.takeIf { it.appliesTo(profile, selected) && !isLte }
        require(servicePolicy == null || serviceRouteSession?.policy == servicePolicy) {
            "Service route control session is unavailable"
        }
        val effective = if (servicePolicy != null) requireNotNull(serviceRouteSession).apply(config) else config
        return json.encodeToString(JsonObject.serializer(), effective)
    }

    /**
     * Levik Game: the TUN carries only the selected games (addAllowedApplication). Their IPv4
     * UDP goes to the Game sidecar, which reaches each game node's relay through the
     * password-protected SOCKS inbound of that node's link; the link itself carries nothing
     * else. DNS, QUIC, TCP and IPv6 of the games go directly.
     */
    fun buildGame(
        config: GameServerConfig,
        tunFileDescriptor: Int,
        endpoint: GameSidecarEndpoint,
        primaryDnsIp: String = PRIMARY_DNS_IP,
        secondaryDnsIp: String = SECONDARY_DNS_IP,
        /** The ordinary server for everything but the match itself; null sends that direct. */
        carrier: TunnelServer? = null,
        /** With a carrier, Russian addresses still go direct, as on an ordinary server. */
        russianDirectCidrs: List<String> = emptyList(),
        xhttpMuxHosts: Set<String> = emptySet(),
    ): String {
        require(tunFileDescriptor >= 0) { "Invalid TUN file descriptor" }
        require(config.routes.isNotEmpty() && config.routes.size == endpoint.socksPorts.size) { "Game routes do not match the sidecar" }
        val carrierOutbound = carrier?.let { server ->
            require(server.engine == TunnelEngineKind.XRAY) { "The Game carrier must be an Xray server" }
            validateServers(listOf(server))
            val repaired = server.copy(outbound = enableXhttpMux(RealityRepair.repair(server.outbound), xhttpMuxHosts))
            requireRealityServerNames(repaired)
            JsonObject(repaired.outbound + ("tag" to JsonPrimitive(GAME_CARRIER_TAG)))
        }
        val other = if (carrierOutbound != null) GAME_CARRIER_TAG else DIRECT_TAG
        val routes = config.routes.map { route ->
            val protocol = (route.outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            require(protocol in SELECTABLE_PROTOCOLS && protocol != "socks") { "Unsupported Game route protocol" }
            JsonObject(RealityRepair.repair(route.outbound) + ("tag" to JsonPrimitive(gameRouteTag(route.index))))
        }
        val socksSettings = buildJsonObject {
            put("auth", "password")
            put("accounts", buildJsonArray {
                add(buildJsonObject {
                    put("user", GAME_SOCKS_USER)
                    put("pass", endpoint.socksPassword)
                })
            })
            put("udp", true)
            put("ip", LOOPBACK)
        }
        fun rule(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", "field")
            build()
        }
        val tun = buildJsonArray { add(JsonPrimitive(TUN_INBOUND_TAG)) }
        val result = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "none") })
            put("env", buildJsonObject { put(TUN_FD_ENV, tunFileDescriptor.toString()) })
            put("dns", buildJsonObject {
                put("queryStrategy", "UseIPv4")
                put("servers", buildJsonArray {
                    add(JsonPrimitive(primaryDnsIp))
                    add(JsonPrimitive(secondaryDnsIp))
                })
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", TUN_INBOUND_TAG)
                    put("protocol", "tun")
                    put("port", 0)
                    put("settings", buildJsonObject {
                        put("name", "levik0")
                        put("mtu", TUN_MTU)
                    })
                    // Game UDP keeps its IP destination: the relay forwards to addresses only.
                    put("sniffing", buildJsonObject { put("enabled", false) })
                })
                config.routes.forEachIndexed { position, route ->
                    add(buildJsonObject {
                        put("tag", gameProbeTag(route.index))
                        put("listen", LOOPBACK)
                        put("port", endpoint.socksPorts[position])
                        put("protocol", "socks")
                        put("settings", socksSettings)
                    })
                }
            })
            put("outbounds", buildJsonArray {
                // First: Xray's own DNS queries and anything unmatched leave directly.
                add(buildJsonObject {
                    put("tag", DIRECT_TAG)
                    put("protocol", "freedom")
                    put("settings", buildJsonObject { put("domainStrategy", "UseIPv4") })
                })
                routes.forEach(::add)
                carrierOutbound?.let(::add)
                add(buildJsonObject {
                    put("tag", GAME_MULTIPATH_TAG)
                    put("protocol", "vless")
                    put("settings", buildJsonObject {
                        put("vnext", buildJsonArray {
                            add(buildJsonObject {
                                put("address", LOOPBACK)
                                put("port", endpoint.port)
                                put("users", buildJsonArray {
                                    add(buildJsonObject {
                                        put("id", endpoint.uuid)
                                        put("encryption", "none")
                                    })
                                })
                            })
                        })
                    })
                    put("mux", buildJsonObject {
                        put("enabled", false)
                        put("xudpConcurrency", -1)
                    })
                })
                add(buildJsonObject {
                    put("tag", BLOCK_TAG)
                    put("protocol", "blackhole")
                    put("settings", buildJsonObject {})
                })
            })
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {
                    config.routes.forEach { route ->
                        // A game node link reaches only that node's relay.
                        add(rule {
                            put("inboundTag", buildJsonArray { add(JsonPrimitive(gameProbeTag(route.index))) })
                            put("ip", buildJsonArray { add(JsonPrimitive(LOOPBACK)) })
                            put("port", GAME_RELAY_PORT.toString())
                            put("network", "udp")
                            put("outboundTag", gameRouteTag(route.index))
                        })
                        add(rule {
                            put("inboundTag", buildJsonArray { add(JsonPrimitive(gameProbeTag(route.index))) })
                            put("outboundTag", BLOCK_TAG)
                        })
                    }
                    add(rule {
                        put("inboundTag", tun)
                        put("ip", JsonArray(BUILT_IN_DIRECT_CIDRS.map(::JsonPrimitive)))
                        put("outboundTag", DIRECT_TAG)
                    })
                    add(rule {
                        put("inboundTag", tun)
                        put("ip", buildJsonArray { add(JsonPrimitive(IPV6_DEFAULT_ROUTE)) })
                        // The carrier is IPv4: IPv6 is refused so that the game falls back to it.
                        put("outboundTag", if (carrierOutbound != null) BLOCK_TAG else DIRECT_TAG)
                    })
                    if (carrierOutbound != null && russianDirectCidrs.isNotEmpty()) {
                        val russian = JsonArray(russianDirectCidrs.distinct().map(::JsonPrimitive))
                        add(rule {
                            put("inboundTag", tun)
                            put("ip", russian)
                            put("network", "tcp")
                            put("outboundTag", DIRECT_TAG)
                        })
                        add(rule {
                            put("inboundTag", tun)
                            put("ip", russian)
                            put("port", "53,443,853")
                            put("network", "udp")
                            put("outboundTag", DIRECT_TAG)
                        })
                    }
                    add(rule {
                        put("inboundTag", tun)
                        put("port", "53,443,853")
                        put("network", "udp")
                        put("outboundTag", other)
                    })
                    // The match itself, Russian game servers included, goes through Levik Game.
                    add(rule {
                        put("inboundTag", tun)
                        put("network", "udp")
                        put("outboundTag", GAME_MULTIPATH_TAG)
                    })
                    add(rule {
                        put("inboundTag", tun)
                        put("network", "tcp")
                        put("outboundTag", other)
                    })
                    add(rule {
                        put("network", "tcp,udp")
                        put("outboundTag", BLOCK_TAG)
                    })
                })
            })
        }
        return json.encodeToString(JsonObject.serializer(), result)
    }

    /**
     * Kill Switch lockdown config: captures the whole TUN traffic and drops it
     * into a blackhole outbound so nothing leaks while the tunnel is down.
     */
    fun buildKillSwitchConfig(tunFileDescriptor: Int): String {
        require(tunFileDescriptor >= 0) { "Invalid TUN file descriptor" }
        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", "none")
            })
            put("env", buildJsonObject {
                put(TUN_FD_ENV, tunFileDescriptor.toString())
            })
            put("dns", buildJsonObject {
                put("queryStrategy", "UseIPv4")
                put("servers", buildJsonArray {
                    add(JsonPrimitive(PRIMARY_DNS_IP))
                })
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", TUN_INBOUND_TAG)
                    put("protocol", "tun")
                    put("port", 0)
                    put("settings", buildJsonObject {
                        put("name", "levik0")
                        put("mtu", TUN_MTU)
                    })
                    put("sniffing", buildJsonObject {
                        put("enabled", false)
                    })
                })
            })
            put("outbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", BLOCK_TAG)
                    put("protocol", "blackhole")
                    put("settings", buildJsonObject {})
                })
            })
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add(JsonPrimitive(TUN_INBOUND_TAG)) })
                        put("network", "tcp,udp")
                        put("outboundTag", BLOCK_TAG)
                    })
                })
            })
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    /**
     * Share links cannot carry Mux settings, so hosts that need Mux.Cool over XHTTP come from the
     * server's remote config. An outbound that already has `mux` keeps it.
     */
    private fun enableXhttpMux(outbound: JsonObject, hosts: Set<String>): JsonObject {
        if (hosts.isEmpty() || "mux" in outbound ||
            (outbound["protocol"] as? JsonPrimitive)?.contentOrNull != "vless"
        ) return outbound

        val stream = outbound["streamSettings"] as? JsonObject ?: return outbound
        val network = (stream["network"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (network != "xhttp" && network != "splithttp") return outbound

        val settings = outbound["settings"] as? JsonObject ?: return outbound
        val servers = settings["vnext"] as? JsonArray ?: return outbound
        if (servers.isEmpty() || servers.any { server ->
                val address = ((server as? JsonObject)?.get("address") as? JsonPrimitive)?.contentOrNull
                address?.lowercase() !in hosts
            }
        ) return outbound

        return buildJsonObject {
            outbound.forEach { (key, value) -> put(key, value) }
            put("mux", buildJsonObject {
                put("enabled", true)
                put("concurrency", 1)
            })
        }
    }

    private fun injectFragmentDialer(outbound: JsonObject): JsonObject {
        val stream = (outbound["streamSettings"] as? JsonObject) ?: buildJsonObject {}
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        val transport = (stream["network"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        // TCP TLS fragmentation must not replace Hysteria's UDP packet socket.
        if (protocol in setOf("hysteria", "hysteria2", "tuic", "wireguard") ||
            transport in setOf("hysteria", "hysteria2", "quic", "kcp", "mkcp")
        ) return outbound
        // A server-chosen Finalmask already shapes the traffic; a second fragmenter would fight it.
        if ("finalmask" in stream) return outbound
        val existingSockopt = (stream["sockopt"] as? JsonObject) ?: buildJsonObject {}
        val updatedSockopt = buildJsonObject {
            existingSockopt.forEach { (k, v) -> put(k, v) }
            put("dialerProxy", FRAGMENT_TAG)
        }
        val updatedStream = buildJsonObject {
            stream.forEach { (k, v) -> put(k, v) }
            put("sockopt", updatedSockopt)
        }
        return buildJsonObject {
            outbound.forEach { (k, v) -> put(k, v) }
            put("streamSettings", updatedStream)
        }
    }

    private fun requireRealityServerNames(server: TunnelServer) {
        val stream = server.outbound["streamSettings"] as? JsonObject ?: return
        val security = (stream["security"] as? JsonPrimitive)?.contentOrNull ?: return
        if (!security.equals("reality", ignoreCase = true)) return
        val reality = stream["realitySettings"] as? JsonObject ?: return
        if (RealityRepair.hasUsableServerName(reality)) return
        throw IllegalArgumentException("REALITY server settings are incomplete")
    }

    private fun sanitizeAntiDpiParam(param: String, fallback: String): String {
        val trimmed = param.trim()
        if (trimmed.isBlank()) return fallback
        return if (trimmed.matches(Regex("^[a-zA-Z0-9,-]+$"))) trimmed else fallback
    }

    private fun validateServers(servers: List<TunnelServer>) {
        require(servers.isNotEmpty() && servers.size <= MAX_SERVERS)
        val tags = mutableSetOf<String>()
        servers.forEach { server ->
            require(server.tag.matches(SAFE_TAG)) { "Invalid outbound tag" }
            require(server.tag !in APP_OWNED_TAGS) { "Outbound tag collides with app routing" }
            require(tags.add(server.tag)) { "Duplicate outbound tag" }
            val protocol = server.outbound["protocol"] as? JsonPrimitive
            require(protocol?.content?.lowercase() in SELECTABLE_PROTOCOLS) {
                "Unsupported outbound protocol"
            }
            require(server.outbound["tag"] == JsonPrimitive(server.tag)) {
                "Outbound tag mismatch"
            }
        }
    }

    companion object {
        const val DEFAULT_ANTI_DPI_PACKETS = "tlshello"
        const val DEFAULT_ANTI_DPI_LENGTH = "100-200"
        const val DEFAULT_ANTI_DPI_INTERVAL = "10-20"
        const val PRIMARY_DNS_ENDPOINT = "1.1.1.1:53"
        private const val PRIMARY_DNS_IP = "1.1.1.1"
        private const val SECONDARY_DNS_IP = "8.8.8.8"
        private const val TUN_FD_ENV = "xray.tun.fd"
        private const val TUN_INBOUND_TAG = "levik-tun-in"
        private const val DIRECT_TAG = "levik-direct"
        private const val BLOCK_TAG = "levik-block"
        private const val FRAGMENT_TAG = "levik-fragment"
        private const val RELAY_PROXY_TAG = "levik-relay-proxy"
        private const val GAME_MULTIPATH_TAG = "levik-game-multipath"
        private const val GAME_SOCKS_USER = "levik-game"
        private const val GAME_RELAY_PORT = 37079
        private const val LOOPBACK = "127.0.0.1"
        internal fun gameRouteTag(index: Int) = "levik-game-route-$index"
        internal fun gameProbeTag(index: Int) = "levik-game-probe-$index"
        private const val IPV6_DEFAULT_ROUTE = "::/0"
        internal const val TUN_MTU = 1500
        internal const val GAME_CARRIER_TAG = "levik-game-carrier"
        private const val MAX_SERVERS = 200
        private val SAFE_TAG = Regex("[A-Za-z0-9._:-]{1,128}")
        private val APP_OWNED_TAGS = setOf(TUN_INBOUND_TAG, DIRECT_TAG, BLOCK_TAG, FRAGMENT_TAG)
        private val SELECTABLE_PROTOCOLS = setOf(
            "vless",
            "vmess",
            "trojan",
            "shadowsocks",
            "hysteria",
            "hysteria2",
            "socks",
        )
        private val BUILT_IN_DIRECT_CIDRS = listOf(
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "224.0.0.0/4",
            "255.255.255.255/32",
            "::1/128",
            "fc00::/7",
            "fe80::/10",
            "ff00::/8",
        )
        private val RUSSIAN_DOMAINS = listOf(
            "domain:ru",
            "domain:su",
            "domain:xn--p1ai",
            "domain:xn--p1acf",
            "domain:xn--80adxhks",
            "domain:xn--d1acj3b",
            "domain:xn--80asehdb",
            "domain:xn--80aswg",
            "domain:xn--c1avg",
            "domain:xn--j1aef",
            "domain:ru.com",
            "domain:ru.net",
        )
        val POPULAR_BLOCKED_DOMAINS = listOf(
            "domain:instagram.com",
            "domain:cdninstagram.com",
            "domain:facebook.com",
            "domain:fbcdn.net",
            "domain:twitter.com",
            "domain:x.com",
            "domain:twimg.com",
            "domain:openai.com",
            "domain:chatgpt.com",
            "domain:oaistatic.com",
            "domain:oaiusercontent.com",
            "domain:claude.ai",
            "domain:anthropic.com",
            "domain:notion.so",
            "domain:notion.site",
            "domain:canva.com",
            "domain:linkedin.com",
            "domain:licdn.com",
            "domain:spotify.com",
            "domain:discord.com",
            "domain:discordapp.com",
            "domain:discord.gg",
            "domain:rutracker.org",
            "domain:flibusta.is",
            "domain:meduza.io",
            "domain:bbc.com",
            "domain:dw.com",
            "domain:svoboda.org",
            "domain:rferl.org",
            "domain:zona.media",
            "domain:theins.ru",
            "domain:novayagazeta.eu",
            "domain:holod.media",
            "domain:vpngenerator.org",
            "domain:ntc.party",
        )
    }
}
