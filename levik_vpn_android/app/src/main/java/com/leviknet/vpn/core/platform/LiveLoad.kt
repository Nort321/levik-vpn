package com.leviknet.vpn.core.platform

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The live part of the app configuration, docs/app-platform.md "Node load":
 * how busy each node is now, which nodes stayed full, and how each node works
 * from the user's operator. About a kilobyte; asked for before an automatic
 * connection and while one runs, never in the background otherwise.
 */
data class LiveLoad(
    /** 0..1 by node id. */
    val load: Map<String, Double>,
    /** Node ids that take no new automatic connections. */
    val full: Set<String>,
    /** Factor by node id, 1 typical; kept from the last answer outside the tunnel. */
    val quality: Map<String, Double>,
    val fetchedAtMs: Long,
    /** When [quality] was measured on the user's own network; null without one. */
    val qualityAtMs: Long?,
    val etag: String?,
)

sealed interface LiveLoadAnswer {
    data class Fresh(
        val load: Map<String, Double>,
        val full: Set<String>,
        /** Null: the answer came through the tunnel and says nothing about the operator. */
        val quality: Map<String, Double>?,
        val etag: String?,
    ) : LiveLoadAnswer

    /** 304: nothing changed since [LiveLoad.etag]. */
    data object Same : LiveLoadAnswer

    /** An older website without the endpoint: the configuration's node load still works. */
    data object Unsupported : LiveLoadAnswer
}

/** What Smart Auto ranks with: the newest load the app has, its age, full nodes and quality. */
data class NodeLoadView(
    val load: Map<String, Double>,
    val ageMs: Long,
    val full: Set<String>,
    val quality: Map<String, Double>,
) {
    companion object {
        val EMPTY = NodeLoadView(emptyMap(), Long.MAX_VALUE, emptySet(), emptyMap())
    }
}

object LiveLoadPolicy {
    /** A load answer is asked for again after this. */
    const val MAX_AGE_MS = 60_000L

    /** While an automatic connection runs on a screen in use: often enough to notice a full node. */
    const val CONNECTED_EVERY_MS = 10 * 60_000L

    /** While the current node is full, to confirm it before moving. */
    const val FULL_RECHECK_MS = 2 * 60_000L

    /** Quality describes the operator at the time; an old one may be about another network. */
    const val QUALITY_TTL_MS = 2 * 60 * 60_000L

    /** A website without the endpoint is asked again only after this. */
    const val UNSUPPORTED_RETRY_MS = 60 * 60_000L

    /** Longer than the configuration's load lives: [SmartServerRanking.LOAD_FORGET_MS]. */
    private const val FORGET_MS = 15 * 60_000L
    private const val MAX_NODES = 200
    private val NODE_ID = Regex("^n1[0-9a-f]{20}$")
    val ETAG = Regex("^\"[A-Za-z0-9_-]{1,64}\"$")

    fun parse(value: JsonElement, etag: String?): LiveLoadAnswer.Fresh? {
        val root = value as? JsonObject ?: return null
        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val quality = root["quality"]
        return LiveLoadAnswer.Fresh(
            load = numbers(root["load"], 0.0, 1.0),
            full = (root["full"] as? JsonArray).orEmpty().asSequence()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                .filter(NODE_ID::matches)
                .take(MAX_NODES)
                .toSet(),
            quality = if (quality == null || quality is JsonNull) null else numbers(quality, 0.5, 2.0),
            etag = etag?.takeIf(ETAG::matches),
        )
    }

    private fun numbers(value: JsonElement?, min: Double, max: Double): Map<String, Double> =
        (value as? JsonObject).orEmpty().entries.asSequence()
            .mapNotNull { (id, raw) ->
                val number = (raw as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
                    ?.takeIf { it.isFinite() && it in min..max }
                if (NODE_ID.matches(id) && number != null) id to number else null
            }
            .take(MAX_NODES)
            .toMap()

    /** The next state after an answer; [throughTunnel] answers keep the previous quality. */
    fun merge(previous: LiveLoad?, answer: LiveLoadAnswer, nowMs: Long, throughTunnel: Boolean): LiveLoad? = when (answer) {
        LiveLoadAnswer.Unsupported -> previous
        LiveLoadAnswer.Same -> previous?.copy(fetchedAtMs = nowMs)
        is LiveLoadAnswer.Fresh -> {
            val fresh = !throughTunnel && answer.quality != null
            LiveLoad(
                load = answer.load,
                full = answer.full,
                quality = if (fresh) answer.quality.orEmpty() else previous?.quality.orEmpty(),
                fetchedAtMs = nowMs,
                qualityAtMs = if (fresh) nowMs else previous?.qualityAtMs,
                etag = answer.etag,
            )
        }
    }

    /**
     * The newest load the app has: the live answer, or the configuration's
     * when that came later (an older website has no live endpoint).
     */
    fun view(live: LiveLoad?, config: StoredRemoteConfig?, nowMs: Long): NodeLoadView {
        val newest = live?.takeIf { config == null || it.fetchedAtMs >= config.fetchedAtMs }
        val (load, fetchedAt) = when {
            newest != null -> newest.load to newest.fetchedAtMs
            config != null -> config.config.nodeLoad to config.fetchedAtMs
            else -> return NodeLoadView.EMPTY
        }
        return NodeLoadView(
            load = load,
            ageMs = (nowMs - fetchedAt).coerceAtLeast(0L),
            full = live?.takeIf { nowMs - it.fetchedAtMs < FORGET_MS }?.full.orEmpty(),
            quality = live?.takeIf { state -> state.qualityAtMs?.let { nowMs - it < QUALITY_TTL_MS } == true }?.quality.orEmpty(),
        )
    }

    /** The load a list shows: full nodes as 1, nothing once the reading is too old to mean anything. */
    fun shown(view: NodeLoadView): Map<String, Double> {
        if (view.ageMs >= FORGET_MS) return emptyMap()
        return view.load + view.full.associateWith { 1.0 }
    }
}
