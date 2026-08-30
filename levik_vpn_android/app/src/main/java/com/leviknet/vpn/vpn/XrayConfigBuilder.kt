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
    ): String {
        require(tunFileDescriptor >= 0) { "Invalid TUN file descriptor" }
        profile.subscriptionExpiresAt?.let { value ->
            require(Instant.parse(value).isAfter(clock.instant())) {
                "Subscription has expired"
            }
        }
        val selected = profile.servers.firstOrNull { it.id == selectedServerId }
            ?: throw IllegalArgumentException("Selected server is unavailable")
        validateServers(profile.servers)

        val orderedServers = (listOf(selected) + profile.servers.filterNot { it.id == selected.id })
            .map { server ->
                var repairedOutbound = RealityRepair.repair(server.outbound)
                if (antiDpiEnabled) {
                    repairedOutbound = injectFragmentDialer(repairedOutbound)
                }
                server.copy(outbound = repairedOutbound)
            }
        orderedServers.forEach(::requireRealityServerNames)

        val isBypassRu = routingPreset == RoutingPreset.BYPASS_RU || bypassRussianTraffic
        val isBlockedOnly = routingPreset == RoutingPreset.BLOCKED_ONLY

        val directCidrs = (
            BUILT_IN_DIRECT_CIDRS +
                profile.directCidrs +
                if (isBypassRu) russianDirectCidrs else emptyList()
            ).distinct()

        val directDomains = buildList {
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

        val proxyDomains = buildList {
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
                put("domainStrategy", if (isBypassRu || isBlockedOnly || directDomains.isNotEmpty()) "IPIfNonMatch" else "AsIs")
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
        return json.encodeToString(JsonObject.serializer(), config)
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

    private fun injectFragmentDialer(outbound: JsonObject): JsonObject {
        val stream = (outbound["streamSettings"] as? JsonObject) ?: buildJsonObject {}
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
        private const val IPV6_DEFAULT_ROUTE = "::/0"
        private const val TUN_MTU = 1500
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
