package com.leviknet.vpn.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SplitTunnelMode {
    OFF,
    DISALLOWED, // Exclude selected apps from VPN
    ALLOWED,    // Only route selected apps through VPN
}

enum class RoutingPreset(
    val titleRu: String,
    val titleEn: String,
    val descriptionRu: String,
    val descriptionEn: String,
) {
    GLOBAL(
        titleRu = "Весь трафик (Global)",
        titleEn = "All Traffic (Global)",
        descriptionRu = "Весь трафик маршрутизируется через защищенный VPN-туннель",
        descriptionEn = "Route all network traffic through the secure VPN tunnel",
    ),
    BYPASS_RU(
        titleRu = "Обход ресурсов РФ",
        titleEn = "Bypass Russian Resources",
        descriptionRu = "Российские сайты, сервисы и IP открываются напрямую",
        descriptionEn = "Russian websites, domains and IP subnets route directly",
    ),
    BLOCKED_ONLY(
        titleRu = "Только заблокированное (Anti-Block)",
        titleEn = "Blocked Services Only",
        descriptionRu = "Через VPN идут только заблокированные сервисы (Instagram, X, ChatGPT, Notion и др.)",
        descriptionEn = "Only popular blocked domains route via VPN, other traffic routes directly",
    ),
}

enum class AntiDpiPreset(
    val titleRu: String,
    val titleEn: String,
    val descriptionRu: String,
    val descriptionEn: String,
    val defaultPackets: String,
    val defaultLength: String,
    val defaultInterval: String,
) {
    OFF(
        titleRu = "Выключено",
        titleEn = "Disabled",
        descriptionRu = "Прямое подключение без фрагментации пакетов",
        descriptionEn = "Direct connection without packet fragmentation",
        defaultPackets = "",
        defaultLength = "",
        defaultInterval = "",
    ),
    TLS_HELLO(
        titleRu = "TLS ClientHello (Рекомендуемый)",
        titleEn = "TLS ClientHello (Recommended)",
        descriptionRu = "Дробление только TLS ClientHello рукопожатия. Минимальное влияние на пинг и скорость.",
        descriptionEn = "Fragments only TLS ClientHello handshake. Minimal impact on latency and speed.",
        defaultPackets = "tlshello",
        defaultLength = "100-200",
        defaultInterval = "10-20",
    ),
    MICRO(
        titleRu = "Микро-фрагментация (Агрессивный)",
        titleEn = "Micro-fragmentation (Aggressive)",
        descriptionRu = "Дробление пакетов на микрочастицы по 1–5 байт. Максимальная пробиваемость жестких блокировок ТСПУ.",
        descriptionEn = "Fragments packets into 1–5 byte chunks. Maximum bypass capability against strict DPI/TSPU filters.",
        defaultPackets = "tlshello",
        defaultLength = "1-5",
        defaultInterval = "5-15",
    ),
    BALANCED(
        titleRu = "Умеренная (1–3 пакета)",
        titleEn = "Balanced (1–3 packets)",
        descriptionRu = "Фрагментация первых 3 пакетов TCP-соединения блоками по 50–150 байт.",
        descriptionEn = "Fragments the first 3 TCP packets into 50–150 byte chunks.",
        defaultPackets = "1-3",
        defaultLength = "50-150",
        defaultInterval = "10-20",
    ),
    DEEP(
        titleRu = "Глубокая (1–5 пакетов)",
        titleEn = "Deep (1–5 packets)",
        descriptionRu = "Фрагментация первых 5 пакетов с увеличенной задержкой между фрагментами для переполнения DPI-буферов.",
        descriptionEn = "Fragments the first 5 packets with increased delay to overflow DPI buffers.",
        defaultPackets = "1-5",
        defaultLength = "30-80",
        defaultInterval = "15-30",
    ),
    CUSTOM(
        titleRu = "Пользовательская",
        titleEn = "Custom",
        descriptionRu = "Ручная настройка номеров пакетов, длины фрагментов и интервалов задержки.",
        descriptionEn = "Manual configuration of packet numbers, chunk lengths, and delay intervals.",
        defaultPackets = "tlshello",
        defaultLength = "100-200",
        defaultInterval = "10-20",
    ),
}

enum class DnsProvider(
    val title: String,
    val primaryIpv4: String,
    val secondaryIpv4: String,
    val primaryIpv6: String,
    val secondaryIpv6: String,
    val dohUrl: String?,
    val description: String,
) {
    CLOUDFLARE(
        title = "Cloudflare (1.1.1.1)",
        primaryIpv4 = "1.1.1.1",
        secondaryIpv4 = "1.0.0.1",
        primaryIpv6 = "2606:4700:4700::1111",
        secondaryIpv6 = "2606:4700:4700::1001",
        dohUrl = "https://1.1.1.1/dns-query",
        description = "Быстрый и приватный DNS с поддержкой DoH",
    ),
    GOOGLE(
        title = "Google (8.8.8.8)",
        primaryIpv4 = "8.8.8.8",
        secondaryIpv4 = "8.8.4.4",
        primaryIpv6 = "2001:4860:4860::8888",
        secondaryIpv6 = "2001:4860:4860::8844",
        dohUrl = "https://dns.google/dns-query",
        description = "Надёжный глобальный DNS от Google с поддержкой DoH",
    ),
    ADGUARD(
        title = "AdGuard DNS (AdBlock)",
        primaryIpv4 = "94.140.14.14",
        secondaryIpv4 = "94.140.15.15",
        primaryIpv6 = "2a10:50c0::ad1:ff",
        secondaryIpv6 = "2a10:50c0::ad2:ff",
        dohUrl = "https://dns.adguard-dns.com/dns-query",
        description = "Блокировка рекламы, трекеров и фишинга через DoH",
    ),
    QUAD9(
        title = "Quad9 (9.9.9.9)",
        primaryIpv4 = "9.9.9.9",
        secondaryIpv4 = "149.112.112.112",
        primaryIpv6 = "2620:fe::fe",
        secondaryIpv6 = "2620:fe::9",
        dohUrl = "https://dns.quad9.net/dns-query",
        description = "Защита от вредоносных сайтов и безопасность через DoH",
    ),
    CUSTOM(
        title = "Пользовательский DNS",
        primaryIpv4 = "1.1.1.1",
        secondaryIpv4 = "8.8.8.8",
        primaryIpv6 = "2606:4700:4700::1111",
        secondaryIpv6 = "2001:4860:4860::8888",
        dohUrl = null,
        description = "Использовать собственные IP-адреса DNS-серверов",
    ),
}

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
    AMOLED,
}

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val mutableRoutingPreset = MutableStateFlow(
        runCatching {
            RoutingPreset.valueOf(
                preferences.getString(ROUTING_PRESET, RoutingPreset.BYPASS_RU.name)
                    ?: RoutingPreset.BYPASS_RU.name,
            )
        }.getOrDefault(RoutingPreset.BYPASS_RU),
    )
    private val mutableBypassRussianTraffic = MutableStateFlow(
        preferences.getBoolean(BYPASS_RUSSIAN_TRAFFIC, true),
    )
    private val mutableAntiDpiPreset = MutableStateFlow(
        runCatching {
            val saved = preferences.getString(ANTI_DPI_PRESET, null)
            if (saved != null) {
                AntiDpiPreset.valueOf(saved)
            } else {
                val legacyEnabled = preferences.getBoolean(ANTI_DPI_ENABLED, false)
                if (legacyEnabled) AntiDpiPreset.TLS_HELLO else AntiDpiPreset.OFF
            }
        }.getOrDefault(AntiDpiPreset.OFF),
    )
    private val mutableAntiDpiPackets = MutableStateFlow(
        preferences.getString(ANTI_DPI_PACKETS, AntiDpiPreset.TLS_HELLO.defaultPackets)
            ?: AntiDpiPreset.TLS_HELLO.defaultPackets,
    )
    private val mutableAntiDpiLength = MutableStateFlow(
        preferences.getString(ANTI_DPI_LENGTH, AntiDpiPreset.TLS_HELLO.defaultLength)
            ?: AntiDpiPreset.TLS_HELLO.defaultLength,
    )
    private val mutableAntiDpiInterval = MutableStateFlow(
        preferences.getString(ANTI_DPI_INTERVAL, AntiDpiPreset.TLS_HELLO.defaultInterval)
            ?: AntiDpiPreset.TLS_HELLO.defaultInterval,
    )
    private val mutableAntiDpiEnabled = MutableStateFlow(
        mutableAntiDpiPreset.value != AntiDpiPreset.OFF,
    )
    private val mutableAutoHealingEnabled = MutableStateFlow(
        preferences.getBoolean(AUTO_HEALING_ENABLED, true),
    )
    private val mutableKillSwitchEnabled = MutableStateFlow(
        preferences.getBoolean(KILL_SWITCH_ENABLED, false),
    )
    private val mutableAutoConnectUntrustedWifi = MutableStateFlow(
        preferences.getBoolean(AUTO_CONNECT_UNTRUSTED_WIFI, false),
    )
    private val mutableTrustedWifiSsids = MutableStateFlow(
        preferences.getStringSet(TRUSTED_WIFI_SSIDS, emptySet()) ?: emptySet(),
    )
    private val mutableUseDoh = MutableStateFlow(
        preferences.getBoolean(USE_DOH, true),
    )
    private val mutableCustomDohUrl = MutableStateFlow(
        preferences.getString(CUSTOM_DOH_URL, "") ?: "",
    )

    private val mutableSelectedSubscriptionId = MutableStateFlow(
        preferences.getString(SELECTED_SUBSCRIPTION_ID, null),
    )
    private val mutableAutomaticServer = MutableStateFlow(
        preferences.getBoolean(AUTOMATIC_SERVER, true),
    )
    private val mutableSplitTunnelMode = MutableStateFlow(
        runCatching {
            SplitTunnelMode.valueOf(
                preferences.getString(SPLIT_TUNNEL_MODE, SplitTunnelMode.OFF.name)
                    ?: SplitTunnelMode.OFF.name,
            )
        }.getOrDefault(SplitTunnelMode.OFF),
    )
    private val mutableSplitTunnelPackages = MutableStateFlow(
        preferences.getStringSet(SPLIT_TUNNEL_PACKAGES, emptySet()) ?: emptySet(),
    )
    private val mutableDnsProvider = MutableStateFlow(
        runCatching {
            DnsProvider.valueOf(
                preferences.getString(DNS_PROVIDER, DnsProvider.CLOUDFLARE.name)
                    ?: DnsProvider.CLOUDFLARE.name,
            )
        }.getOrDefault(DnsProvider.CLOUDFLARE),
    )
    private val mutableCustomDnsIpv4 = MutableStateFlow(
        preferences.getString(CUSTOM_DNS_IPV4, "1.1.1.1") ?: "1.1.1.1",
    )
    private val mutableThemeMode = MutableStateFlow(
        runCatching {
            ThemeMode.valueOf(
                preferences.getString(THEME_MODE, ThemeMode.SYSTEM.name)
                    ?: ThemeMode.SYSTEM.name,
            )
        }.getOrDefault(ThemeMode.SYSTEM),
    )
    private val mutableUseDynamicColors = MutableStateFlow(
        preferences.getBoolean(USE_DYNAMIC_COLORS, false),
    )
    private val mutableAutoConnectOnBoot = MutableStateFlow(
        preferences.getBoolean(AUTO_CONNECT_ON_BOOT, false),
    )
    private val mutableAutoFallbackServer = MutableStateFlow(
        preferences.getBoolean(AUTO_FALLBACK_SERVER, true),
    )
    private val mutableFavoriteServerIds = MutableStateFlow(
        preferences.getStringSet(FAVORITE_SERVER_IDS, emptySet()) ?: emptySet(),
    )
    private val mutableCustomDirectDomains = MutableStateFlow(
        preferences.getStringSet(CUSTOM_DIRECT_DOMAINS, emptySet()) ?: emptySet(),
    )
    private val mutableCustomProxyDomains = MutableStateFlow(
        preferences.getStringSet(CUSTOM_PROXY_DOMAINS, emptySet()) ?: emptySet(),
    )
    private val mutableAnonymousTelemetryEnabled = MutableStateFlow(
        preferences.getBoolean(ANONYMOUS_TELEMETRY_ENABLED, false),
    )
    private val mutablePausedUntilMs = MutableStateFlow(
        preferences.getLong(PAUSED_UNTIL_MS, 0L),
    )

    val routingPreset: StateFlow<RoutingPreset> = mutableRoutingPreset.asStateFlow()
    val bypassRussianTraffic: StateFlow<Boolean> = mutableBypassRussianTraffic.asStateFlow()
    val antiDpiPreset: StateFlow<AntiDpiPreset> = mutableAntiDpiPreset.asStateFlow()
    val antiDpiPackets: StateFlow<String> = mutableAntiDpiPackets.asStateFlow()
    val antiDpiLength: StateFlow<String> = mutableAntiDpiLength.asStateFlow()
    val antiDpiInterval: StateFlow<String> = mutableAntiDpiInterval.asStateFlow()
    val antiDpiEnabled: StateFlow<Boolean> = mutableAntiDpiEnabled.asStateFlow()
    val autoHealingEnabled: StateFlow<Boolean> = mutableAutoHealingEnabled.asStateFlow()
    val killSwitchEnabled: StateFlow<Boolean> = mutableKillSwitchEnabled.asStateFlow()
    val autoConnectUntrustedWifi: StateFlow<Boolean> = mutableAutoConnectUntrustedWifi.asStateFlow()
    val trustedWifiSsids: StateFlow<Set<String>> = mutableTrustedWifiSsids.asStateFlow()
    val useDoh: StateFlow<Boolean> = mutableUseDoh.asStateFlow()
    val customDohUrl: StateFlow<String> = mutableCustomDohUrl.asStateFlow()

    val selectedSubscriptionId: StateFlow<String?> = mutableSelectedSubscriptionId.asStateFlow()
    val automaticServer: StateFlow<Boolean> = mutableAutomaticServer.asStateFlow()
    val splitTunnelMode: StateFlow<SplitTunnelMode> = mutableSplitTunnelMode.asStateFlow()
    val splitTunnelPackages: StateFlow<Set<String>> = mutableSplitTunnelPackages.asStateFlow()
    val dnsProvider: StateFlow<DnsProvider> = mutableDnsProvider.asStateFlow()
    val customDnsIpv4: StateFlow<String> = mutableCustomDnsIpv4.asStateFlow()
    val themeMode: StateFlow<ThemeMode> = mutableThemeMode.asStateFlow()
    val useDynamicColors: StateFlow<Boolean> = mutableUseDynamicColors.asStateFlow()
    val autoConnectOnBoot: StateFlow<Boolean> = mutableAutoConnectOnBoot.asStateFlow()
    val autoFallbackServer: StateFlow<Boolean> = mutableAutoFallbackServer.asStateFlow()
    val favoriteServerIds: StateFlow<Set<String>> = mutableFavoriteServerIds.asStateFlow()
    val customDirectDomains: StateFlow<Set<String>> = mutableCustomDirectDomains.asStateFlow()
    val customProxyDomains: StateFlow<Set<String>> = mutableCustomProxyDomains.asStateFlow()
    val anonymousTelemetryEnabled: StateFlow<Boolean> = mutableAnonymousTelemetryEnabled.asStateFlow()
    val pausedUntilMs: StateFlow<Long> = mutablePausedUntilMs.asStateFlow()

    fun setRoutingPreset(preset: RoutingPreset) {
        preferences.edit(commit = true) {
            putString(ROUTING_PRESET, preset.name)
            putBoolean(BYPASS_RUSSIAN_TRAFFIC, preset == RoutingPreset.BYPASS_RU)
        }
        mutableRoutingPreset.value = preset
        mutableBypassRussianTraffic.value = (preset == RoutingPreset.BYPASS_RU)
    }

    fun setBypassRussianTraffic(enabled: Boolean) {
        val preset = if (enabled) RoutingPreset.BYPASS_RU else RoutingPreset.GLOBAL
        setRoutingPreset(preset)
    }

    fun setAntiDpiPreset(preset: AntiDpiPreset) {
        preferences.edit(commit = true) {
            putString(ANTI_DPI_PRESET, preset.name)
            putBoolean(ANTI_DPI_ENABLED, preset != AntiDpiPreset.OFF)
            if (preset != AntiDpiPreset.CUSTOM && preset != AntiDpiPreset.OFF) {
                putString(ANTI_DPI_PACKETS, preset.defaultPackets)
                putString(ANTI_DPI_LENGTH, preset.defaultLength)
                putString(ANTI_DPI_INTERVAL, preset.defaultInterval)
            }
        }
        mutableAntiDpiPreset.value = preset
        mutableAntiDpiEnabled.value = (preset != AntiDpiPreset.OFF)
        if (preset != AntiDpiPreset.CUSTOM && preset != AntiDpiPreset.OFF) {
            mutableAntiDpiPackets.value = preset.defaultPackets
            mutableAntiDpiLength.value = preset.defaultLength
            mutableAntiDpiInterval.value = preset.defaultInterval
        }
    }

    fun setAntiDpiCustomParams(packets: String, length: String, interval: String) {
        val cleanPackets = packets.trim().ifBlank { "tlshello" }
        val cleanLength = length.trim().ifBlank { "100-200" }
        val cleanInterval = interval.trim().ifBlank { "10-20" }
        preferences.edit(commit = true) {
            putString(ANTI_DPI_PRESET, AntiDpiPreset.CUSTOM.name)
            putBoolean(ANTI_DPI_ENABLED, true)
            putString(ANTI_DPI_PACKETS, cleanPackets)
            putString(ANTI_DPI_LENGTH, cleanLength)
            putString(ANTI_DPI_INTERVAL, cleanInterval)
        }
        mutableAntiDpiPreset.value = AntiDpiPreset.CUSTOM
        mutableAntiDpiEnabled.value = true
        mutableAntiDpiPackets.value = cleanPackets
        mutableAntiDpiLength.value = cleanLength
        mutableAntiDpiInterval.value = cleanInterval
    }

    fun setAntiDpiEnabled(enabled: Boolean) {
        if (enabled) {
            if (mutableAntiDpiPreset.value == AntiDpiPreset.OFF) {
                setAntiDpiPreset(AntiDpiPreset.TLS_HELLO)
            }
        } else {
            setAntiDpiPreset(AntiDpiPreset.OFF)
        }
    }

    fun setAutoHealingEnabled(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(AUTO_HEALING_ENABLED, enabled)
        }
        mutableAutoHealingEnabled.value = enabled
    }

    fun setKillSwitchEnabled(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(KILL_SWITCH_ENABLED, enabled)
        }
        mutableKillSwitchEnabled.value = enabled
    }

    fun setAutoConnectUntrustedWifi(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(AUTO_CONNECT_UNTRUSTED_WIFI, enabled)
        }
        mutableAutoConnectUntrustedWifi.value = enabled
    }

    fun addTrustedWifiSsid(ssid: String) {
        val clean = ssid.trim().removeSurrounding("\"")
        if (clean.isNotBlank()) {
            val current = mutableTrustedWifiSsids.value.toMutableSet()
            current.add(clean)
            preferences.edit(commit = true) {
                putStringSet(TRUSTED_WIFI_SSIDS, current)
            }
            mutableTrustedWifiSsids.value = current
        }
    }

    fun removeTrustedWifiSsid(ssid: String) {
        val current = mutableTrustedWifiSsids.value.toMutableSet()
        current.remove(ssid)
        preferences.edit(commit = true) {
            putStringSet(TRUSTED_WIFI_SSIDS, current)
        }
        mutableTrustedWifiSsids.value = current
    }

    fun setUseDoh(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(USE_DOH, enabled)
        }
        mutableUseDoh.value = enabled
    }

    fun setCustomDohUrl(url: String) {
        val clean = url.trim()
        preferences.edit(commit = true) {
            putString(CUSTOM_DOH_URL, clean)
        }
        mutableCustomDohUrl.value = clean
    }

    fun setSelectedSubscriptionId(subscriptionId: String?) {
        preferences.edit(commit = true) {
            if (subscriptionId == null) {
                remove(SELECTED_SUBSCRIPTION_ID)
            } else {
                putString(SELECTED_SUBSCRIPTION_ID, subscriptionId)
            }
        }
        mutableSelectedSubscriptionId.value = subscriptionId
    }

    fun setAutomaticServer(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(AUTOMATIC_SERVER, enabled)
        }
        mutableAutomaticServer.value = enabled
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        preferences.edit(commit = true) {
            putString(SPLIT_TUNNEL_MODE, mode.name)
        }
        mutableSplitTunnelMode.value = mode
    }

    fun setSplitTunnelPackages(packages: Set<String>) {
        preferences.edit(commit = true) {
            putStringSet(SPLIT_TUNNEL_PACKAGES, packages)
        }
        mutableSplitTunnelPackages.value = packages
    }

    fun setDnsProvider(provider: DnsProvider) {
        preferences.edit(commit = true) {
            putString(DNS_PROVIDER, provider.name)
        }
        mutableDnsProvider.value = provider
    }

    fun setCustomDnsIpv4(ip: String) {
        val sanitized = ip.trim()
        preferences.edit(commit = true) {
            putString(CUSTOM_DNS_IPV4, sanitized)
        }
        mutableCustomDnsIpv4.value = sanitized
    }

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit(commit = true) {
            putString(THEME_MODE, mode.name)
        }
        mutableThemeMode.value = mode
    }

    fun setUseDynamicColors(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(USE_DYNAMIC_COLORS, enabled)
        }
        mutableUseDynamicColors.value = enabled
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(AUTO_CONNECT_ON_BOOT, enabled)
        }
        mutableAutoConnectOnBoot.value = enabled
    }

    fun setAutoFallbackServer(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(AUTO_FALLBACK_SERVER, enabled)
        }
        mutableAutoFallbackServer.value = enabled
    }

    fun toggleFavoriteServer(serverId: String) {
        val current = mutableFavoriteServerIds.value.toMutableSet()
        if (current.contains(serverId)) {
            current.remove(serverId)
        } else {
            current.add(serverId)
        }
        preferences.edit(commit = true) {
            putStringSet(FAVORITE_SERVER_IDS, current)
        }
        mutableFavoriteServerIds.value = current
    }

    fun setCustomDirectDomains(domains: Set<String>) {
        preferences.edit(commit = true) {
            putStringSet(CUSTOM_DIRECT_DOMAINS, domains)
        }
        mutableCustomDirectDomains.value = domains
    }

    fun setCustomProxyDomains(domains: Set<String>) {
        preferences.edit(commit = true) {
            putStringSet(CUSTOM_PROXY_DOMAINS, domains)
        }
        mutableCustomProxyDomains.value = domains
    }

    fun setAnonymousTelemetryEnabled(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(ANONYMOUS_TELEMETRY_ENABLED, enabled)
        }
        mutableAnonymousTelemetryEnabled.value = enabled
    }

    fun setPausedUntilMs(timestampMs: Long) {
        preferences.edit(commit = true) {
            putLong(PAUSED_UNTIL_MS, timestampMs)
        }
        mutablePausedUntilMs.value = timestampMs
    }

    fun getLastNotifiedExpireMilestone(subscriptionId: String): String? {
        return preferences.getString("notif_expire_${subscriptionId}", null)
    }

    fun setLastNotifiedExpireMilestone(subscriptionId: String, milestone: String) {
        preferences.edit(commit = true) {
            putString("notif_expire_${subscriptionId}", milestone)
        }
    }

    fun getLastNotifiedTrafficMilestone(subscriptionId: String): String? {
        return preferences.getString("notif_traffic_${subscriptionId}", null)
    }

    fun setLastNotifiedTrafficMilestone(subscriptionId: String, milestone: String) {
        preferences.edit(commit = true) {
            putString("notif_traffic_${subscriptionId}", milestone)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "levik_settings_v1"
        private const val ROUTING_PRESET = "routing_preset"
        private const val BYPASS_RUSSIAN_TRAFFIC = "bypass_russian_traffic"
        private const val ANTI_DPI_ENABLED = "anti_dpi_enabled"
        private const val ANTI_DPI_PRESET = "anti_dpi_preset"
        private const val ANTI_DPI_PACKETS = "anti_dpi_packets"
        private const val ANTI_DPI_LENGTH = "anti_dpi_length"
        private const val ANTI_DPI_INTERVAL = "anti_dpi_interval"
        private const val AUTO_HEALING_ENABLED = "auto_healing_enabled"
        private const val KILL_SWITCH_ENABLED = "kill_switch_enabled"
        private const val AUTO_CONNECT_UNTRUSTED_WIFI = "auto_connect_untrusted_wifi"
        private const val TRUSTED_WIFI_SSIDS = "trusted_wifi_ssids"
        private const val USE_DOH = "use_doh"
        private const val CUSTOM_DOH_URL = "custom_doh_url"
        private const val SELECTED_SUBSCRIPTION_ID = "selected_subscription_id"
        private const val AUTOMATIC_SERVER = "automatic_server"
        private const val SPLIT_TUNNEL_MODE = "split_tunnel_mode"
        private const val SPLIT_TUNNEL_PACKAGES = "split_tunnel_packages"
        private const val DNS_PROVIDER = "dns_provider"
        private const val CUSTOM_DNS_IPV4 = "custom_dns_ipv4"
        private const val THEME_MODE = "theme_mode"
        private const val USE_DYNAMIC_COLORS = "use_dynamic_colors"
        private const val AUTO_CONNECT_ON_BOOT = "auto_connect_on_boot"
        private const val AUTO_FALLBACK_SERVER = "auto_fallback_server"
        private const val FAVORITE_SERVER_IDS = "favorite_server_ids"
        private const val CUSTOM_DIRECT_DOMAINS = "custom_direct_domains"
        private const val CUSTOM_PROXY_DOMAINS = "custom_proxy_domains"
        private const val ANONYMOUS_TELEMETRY_ENABLED = "anonymous_telemetry_enabled"
        private const val PAUSED_UNTIL_MS = "paused_until_ms"
    }
}
