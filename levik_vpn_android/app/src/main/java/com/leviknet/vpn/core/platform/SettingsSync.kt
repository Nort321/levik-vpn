package com.leviknet.vpn.core.platform

import com.leviknet.vpn.data.AntiDpiPreset
import com.leviknet.vpn.data.RoutingPreset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Settings that mean the same thing in every Levik VPN app, docs/app-platform.md.
 * Split tunnelling, DNS, theme, autostart and favourites stay on this phone.
 * Values are kept in their wire form: key to JSON primitive.
 */
typealias SyncedSettings = Map<String, JsonPrimitive>

data class SettingsDocument(val settings: SyncedSettings, val revision: Long)

/** The shared settings as this app stores them. */
data class LocalSyncedValues(
    val routing: RoutingPreset,
    val automaticServer: Boolean,
    val autoReconnect: Boolean,
    val killSwitch: Boolean,
    val useDoh: Boolean,
    val antiDpiEnabled: Boolean,
    val antiDpiPackets: String,
    val antiDpiLength: String,
    val antiDpiInterval: String,
)

object SettingsSyncPolicy {
    const val ROUTING_MODE = "routingMode"
    const val AUTOMATIC_SERVER = "automaticServer"
    const val AUTO_RECONNECT = "autoReconnect"
    const val KILL_SWITCH = "killSwitch"
    const val USE_DOH = "useDoh"
    const val ANTI_DPI_ENABLED = "antiDpiEnabled"
    const val ANTI_DPI_PACKETS = "antiDpiPackets"
    const val ANTI_DPI_LENGTH = "antiDpiLength"
    const val ANTI_DPI_INTERVAL = "antiDpiInterval"

    val KEYS = listOf(
        ROUTING_MODE, AUTOMATIC_SERVER, AUTO_RECONNECT, KILL_SWITCH, USE_DOH,
        ANTI_DPI_ENABLED, ANTI_DPI_PACKETS, ANTI_DPI_LENGTH, ANTI_DPI_INTERVAL,
    )
    val ANTI_DPI_KEYS = setOf(ANTI_DPI_ENABLED, ANTI_DPI_PACKETS, ANTI_DPI_LENGTH, ANTI_DPI_INTERVAL)

    private val ROUTING = mapOf(
        RoutingPreset.GLOBAL to "global",
        RoutingPreset.BYPASS_RU to "bypassRu",
        RoutingPreset.BLOCKED_ONLY to "blockedOnly",
    )
    private val PACKETS = Regex("^(tlshello|[0-9]{1,3}(-[0-9]{1,3})?)?$")
    private val RANGE = Regex("^([0-9]{1,4}(-[0-9]{1,4})?)?$")

    /** Only values every app (and the server) accepts are shared. */
    fun isPortable(key: String, value: JsonPrimitive?): Boolean {
        if (value == null) return false
        return when (key) {
            ROUTING_MODE -> value.isString && value.content in ROUTING.values
            ANTI_DPI_PACKETS -> value.isString && value.content.length <= 24 && PACKETS.matches(value.content)
            ANTI_DPI_LENGTH, ANTI_DPI_INTERVAL ->
                value.isString && value.content.length <= 16 && RANGE.matches(value.content)
            in KEYS -> !value.isString && value.booleanOrNull != null
            else -> false
        }
    }

    fun encode(values: LocalSyncedValues): SyncedSettings = mapOf(
        ROUTING_MODE to JsonPrimitive(ROUTING.getValue(values.routing)),
        AUTOMATIC_SERVER to JsonPrimitive(values.automaticServer),
        AUTO_RECONNECT to JsonPrimitive(values.autoReconnect),
        KILL_SWITCH to JsonPrimitive(values.killSwitch),
        USE_DOH to JsonPrimitive(values.useDoh),
        ANTI_DPI_ENABLED to JsonPrimitive(values.antiDpiEnabled),
        ANTI_DPI_PACKETS to JsonPrimitive(values.antiDpiPackets),
        ANTI_DPI_LENGTH to JsonPrimitive(values.antiDpiLength),
        ANTI_DPI_INTERVAL to JsonPrimitive(values.antiDpiInterval),
    )

    fun portable(settings: SyncedSettings): SyncedSettings = settings.filter { (key, value) -> isPortable(key, value) }

    /** The shared fields that changed between two snapshots. */
    fun changes(before: SyncedSettings, after: SyncedSettings): SyncedSettings =
        KEYS.mapNotNull { key ->
            val value = after[key]
            if (value != before[key] && isPortable(key, value)) key to value!! else null
        }.toMap()

    /**
     * What the account has that differs here. Fields with unsent local edits stay,
     * and so do local values other apps cannot express (they were never shared).
     */
    fun remotePatch(local: SyncedSettings, remote: SyncedSettings, pending: SyncedSettings): SyncedSettings =
        KEYS.mapNotNull { key ->
            val value = remote[key] ?: return@mapNotNull null
            if (key in pending || local[key] == value || !isPortable(key, local[key])) null else key to value
        }.toMap()

    /** Pending edits that the server has not confirmed yet. */
    fun withoutConfirmed(pending: SyncedSettings, sent: SyncedSettings): SyncedSettings =
        pending.filter { (key, value) -> sent[key] != value }

    fun parseSettings(value: JsonElement?): SyncedSettings {
        val settings = value as? JsonObject ?: return emptyMap()
        return KEYS.mapNotNull { key ->
            val item = settings[key] as? JsonPrimitive
            if (isPortable(key, item)) key to item!! else null
        }.toMap()
    }

    fun parseDocument(value: JsonElement): SettingsDocument? {
        val root = value as? JsonObject ?: return null
        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val revision = (root["revision"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?.takeIf { it >= 0 } ?: return null
        if (root["settings"] !is JsonObject) return null
        return SettingsDocument(parseSettings(root["settings"]), revision)
    }

    fun routingPreset(value: JsonPrimitive?): RoutingPreset? =
        ROUTING.entries.firstOrNull { it.value == value?.content }?.key

    /** A named preset with these parameters, otherwise the custom one. */
    fun antiDpiPreset(enabled: Boolean, packets: String, length: String, interval: String): AntiDpiPreset {
        if (!enabled) return AntiDpiPreset.OFF
        return AntiDpiPreset.entries.firstOrNull {
            it != AntiDpiPreset.OFF && it != AntiDpiPreset.CUSTOM &&
                it.defaultPackets == packets && it.defaultLength == length && it.defaultInterval == interval
        } ?: AntiDpiPreset.CUSTOM
    }
}
