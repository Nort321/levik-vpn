package com.leviknet.vpn.core.platform

import com.leviknet.vpn.core.auth.ExternalUriPolicy
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Anonymous app configuration from the website, docs/app-platform.md:
 * switches, announcements and the protocols that work best on the current
 * operator. Requests carry only the platform and version.
 */

enum class AnnouncementLevel(val wire: String) {
    INFO("info"),
    WARNING("warning"),
    CRITICAL("critical"),
}

data class AppAnnouncement(
    val id: String,
    val level: AnnouncementLevel,
    val title: String,
    val body: String,
    /** Only Levik website or Telegram links; others are dropped. */
    val linkUrl: String?,
    val notify: Boolean,
    val startsAtMs: Long,
    val endsAtMs: Long,
)

data class RemoteFlag(val enabled: Boolean, val rolloutPercent: Int)

data class ProtocolAdvice(val preferred: List<String>, val avoid: List<String>)

data class RemoteConfig(
    val flags: Map<String, RemoteFlag>,
    val announcements: List<AppAnnouncement>,
    val protocols: ProtocolAdvice?,
    val refreshAfterSeconds: Long,
    /** Hosts whose XHTTP outbound needs Mux.Cool; the server decides, the app keeps no host list. */
    val xhttpMuxHosts: Set<String> = emptySet(),
)

data class StoredRemoteConfig(
    /** The server response as received; parsed again on load. */
    val response: JsonElement,
    val config: RemoteConfig,
    val fetchedAtMs: Long,
    /** Advice measured on the physical network; through the tunnel the server sees a Levik node. */
    val advice: ProtocolAdvice?,
    val adviceAtMs: Long?,
)

object RemoteConfigPolicy {
    /** Advice describes the operator at the time; an old one may be about another network. */
    const val PROTOCOL_ADVICE_TTL_MS = 6 * 60 * 60 * 1_000L
    private const val MIN_REFRESH_S = 5 * 60L
    private const val MAX_REFRESH_S = 24 * 60 * 60L
    private const val DEFAULT_REFRESH_S = 15 * 60L
    private const val MAX_ANNOUNCEMENTS = 20
    private const val MAX_ADVICE = 16
    private val PROTOCOL = Regex("^[a-z0-9-]{1,24}$")
    private val FLAG_KEY = Regex("^[a-z][a-z0-9_]{1,47}$")
    private val ANNOUNCEMENT_ID = Regex("^[0-9a-f-]{36}$")
    private const val MAX_HOSTS = 32
    private val HOSTNAME = Regex("^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")

    fun parse(value: JsonElement): RemoteConfig? {
        val root = value as? JsonObject ?: return null
        if (root.bool("ok") != true) return null
        val refresh = (root["refreshAfterSeconds"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() }
            ?.let { Math.round(it).coerceIn(MIN_REFRESH_S, MAX_REFRESH_S) }
            ?: DEFAULT_REFRESH_S
        val flags = (root["flags"] as? JsonObject).orEmpty().mapNotNull { (key, raw) ->
            val flag = raw as? JsonObject ?: return@mapNotNull null
            val enabled = flag.bool("enabled")
            if (!FLAG_KEY.matches(key) || enabled == null) return@mapNotNull null
            val percent = (flag["rolloutPercent"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it.isFinite() }?.let { Math.round(it).toInt() } ?: 100
            key to RemoteFlag(enabled, percent.coerceIn(0, 100))
        }.toMap()
        val announcements = (root["announcements"] as? JsonArray).orEmpty()
            .take(MAX_ANNOUNCEMENTS)
            .mapNotNull(::parseAnnouncement)
        return RemoteConfig(
            flags = flags,
            announcements = announcements,
            protocols = parseAdvice(root["protocols"]),
            refreshAfterSeconds = refresh,
            xhttpMuxHosts = parseHosts((root["transport"] as? JsonObject)?.get("xhttpMuxHosts")),
        )
    }

    private fun parseHosts(value: JsonElement?): Set<String> = (value as? JsonArray).orEmpty()
        .asSequence()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.lowercase() }
        .filter(HOSTNAME::matches)
        .take(MAX_HOSTS)
        .toSet()

    private fun parseAnnouncement(value: JsonElement): AppAnnouncement? {
        val item = value as? JsonObject ?: return null
        val id = item.text("id")?.takeIf(ANNOUNCEMENT_ID::matches) ?: return null
        val level = AnnouncementLevel.entries.firstOrNull { it.wire == item.text("level") } ?: return null
        val title = item.text("title")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
        val body = item.text("body")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 600 } ?: return null
        val starts = item.text("startsAt")?.let(::instantMs) ?: return null
        val ends = item.text("endsAt")?.let(::instantMs) ?: return null
        if (ends <= starts) return null
        return AppAnnouncement(
            id = id,
            level = level,
            title = title,
            body = body,
            linkUrl = item.text("linkUrl")?.takeIf(::isAllowedLink),
            notify = item.bool("notify") == true,
            startsAtMs = starts,
            endsAtMs = ends,
        )
    }

    fun parseAdvice(value: JsonElement?): ProtocolAdvice? {
        val advice = value as? JsonObject ?: return null
        val preferred = advice["preferred"] as? JsonArray ?: return null
        val avoid = advice["avoid"] as? JsonArray ?: return null
        fun list(items: JsonArray) = items
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .filter(PROTOCOL::matches)
            .take(MAX_ADVICE)
        return ProtocolAdvice(list(preferred), list(avoid))
    }

    /** Announcements to show now, without the ones the user closed. */
    fun visible(config: RemoteConfig?, dismissed: Set<String>, nowMs: Long): List<AppAnnouncement> =
        config?.announcements.orEmpty().filter {
            it.startsAtMs <= nowMs && nowMs < it.endsAtMs && it.id !in dismissed
        }

    /**
     * A gradual rollout: the bucket comes from a random salt that never leaves
     * the phone, so the server cannot tell which installs got a feature.
     */
    fun flagEnabled(config: RemoteConfig?, key: String, salt: String): Boolean {
        val flag = config?.flags?.get(key) ?: return false
        if (!flag.enabled) return false
        if (flag.rolloutPercent >= 100) return true
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt:$key".toByteArray(Charsets.UTF_8))
        val bucket = (((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)) % 100
        return bucket < flag.rolloutPercent
    }

    /** Advice is used only while it is fresh. */
    fun currentAdvice(stored: StoredRemoteConfig?, nowMs: Long): ProtocolAdvice? {
        val advice = stored?.advice ?: return null
        val at = stored.adviceAtMs ?: return null
        return advice.takeIf { nowMs - at < PROTOCOL_ADVICE_TTL_MS }
    }

    /** Keeps the previous advice when this response did not come over the physical network or had none. */
    fun merge(
        previous: StoredRemoteConfig?,
        response: JsonElement,
        config: RemoteConfig,
        nowMs: Long,
        direct: Boolean,
    ): StoredRemoteConfig {
        val fresh = direct && config.protocols != null
        return StoredRemoteConfig(
            response = response,
            config = config,
            fetchedAtMs = nowMs,
            advice = if (fresh) config.protocols else previous?.advice,
            adviceAtMs = if (fresh) nowMs else previous?.adviceAtMs,
        )
    }

    /**
     * Narrows the automatic choice: protocols that mostly fail on this operator
     * are skipped, and those that work well are tried first. Latency still picks
     * the server within the narrowed set.
     */
    fun <Server> candidates(
        servers: List<Server>,
        advice: ProtocolAdvice?,
        protocolOf: (Server) -> String,
    ): List<Server> {
        if (advice == null) return servers
        val avoid = advice.avoid.toSet()
        val pool = servers.filter { protocolOf(it) !in avoid }.ifEmpty { servers }
        val preferred = advice.preferred.toSet()
        return pool.filter { protocolOf(it) in preferred }.ifEmpty { pool }
    }

    fun encodeStored(stored: StoredRemoteConfig): JsonObject = buildJsonObject {
        put("response", stored.response)
        put("fetchedAt", stored.fetchedAtMs)
        put("advice", stored.advice?.let { advice ->
            buildJsonObject {
                put("preferred", JsonArray(advice.preferred.map(::JsonPrimitive)))
                put("avoid", JsonArray(advice.avoid.map(::JsonPrimitive)))
            }
        } ?: JsonNull)
        put("adviceAt", stored.adviceAtMs?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun decodeStored(value: JsonElement): StoredRemoteConfig? {
        val root = value as? JsonObject ?: return null
        val response = root["response"] ?: return null
        val config = parse(response) ?: return null
        val fetchedAt = (root["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: return null
        val advice = parseAdvice(root["advice"])
        val adviceAt = (root["adviceAt"] as? JsonPrimitive)?.longOrNull
        return StoredRemoteConfig(response, config, fetchedAt, advice, adviceAt.takeIf { advice != null })
    }

    private fun isAllowedLink(value: String): Boolean {
        if (value.length > 300) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.host?.let(ExternalUriPolicy::isAllowedHttpsHost) == true
    }

    private fun instantMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
}
