package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.ProtocolAdvice
import java.nio.ByteBuffer
import java.security.MessageDigest

/** What Smart Auto knows about one server before connecting. */
data class SmartCandidate(
    val id: String,
    /** The protocol's telemetry name: VLESS Reality and VLESS XHTTP are different rungs. */
    val protocol: String,
    /** Time to reach the server; null when it did not answer. */
    val latencyMs: Long?,
    /** True when no answer means the server is down (TCP); a UDP server may just not answer probes. */
    val silenceMeansDown: Boolean,
    /** 0 (idle) to 1 (full), already aged by [SmartServerRanking.agedLoad]; null when the website did not say. */
    val load: Double?,
    val verdict: ProtocolVerdict,
    /** How this node works from the user's operator, 1 typical; null when unknown. */
    val quality: Double? = null,
    /** The node stayed full: no new automatic connections while others remain. */
    val full: Boolean = false,
)

data class RankedServer(val id: String, val protocol: String, val score: Double)

/** A mobile server under allowlists, docs/app-platform.md "Allowlists". */
data class AllowlistCandidate(
    val candidate: SmartCandidate,
    /** WDTT or Yandex: tried after the ordinary mobile servers unless the user is signed in to it. */
    val relay: Boolean,
    /** A mobile server that carried nothing under this network's allowlists a moment ago. */
    val failedRecently: Boolean,
    /**
     * A relay the user is already signed in to (a stored VK login, a usable
     * Yandex sign-in): no sign-in to fear, so it goes before the ordinary
     * mobile servers.
     */
    val signedIn: Boolean = false,
    /** The mobile server that last carried traffic under this network's allowlists. */
    val lastWinner: Boolean = false,
)

/**
 * Smart Auto, docs/app-platform.md "Smart Auto": the best server is the one
 * that carries traffic on this network, answers fast and is not busy.
 *
 * score = latency × 1 / (1 − load) × history × advice × quality; lower is better.
 * Protocols the phone saw blocked here and nodes that stayed full are left
 * out; the operator advice from the website only counts until the phone has
 * its own experience.
 */
object SmartServerRanking {
    /** Assumed for a UDP server that does not answer probes (obfuscated or filtered). */
    const val UNKNOWN_LATENCY_MS = 400L

    /** Assumed when the website did not report the load. */
    const val UNKNOWN_LOAD = 0.35

    /** The load curve stops here: a full node is twenty times as slow, not infinitely. */
    private const val MAX_LOAD = 0.95

    /** A load reading counts fully this long, then fades into "unknown" until [LOAD_FORGET_MS]. */
    const val LOAD_TRUST_MS = 2 * 60_000L
    const val LOAD_FORGET_MS = 15 * 60_000L

    /** Servers this close to the best share new connections, so not everyone lands on one. */
    private const val SPREAD_MARGIN = 1.15
    private const val SPREAD_MAX = 3
    private const val SPREAD_BUCKET_MS = 10 * 60_000L
    private const val MIN_QUALITY = 0.7
    private const val MAX_QUALITY = 1.6

    /** Stage 4: how long a node must stay full before a running connection leaves it. */
    const val OVERLOAD_CONFIRM_MS = 5 * 60_000L
    const val OVERLOAD_MOVE_COOLDOWN_MS = 30 * 60_000L

    /** The new server must be at least this much better: a move costs a reconnect. */
    const val OVERLOAD_MOVE_MARGIN = 0.6

    /** A running connection moves only to a server this much better. */
    const val SWITCH_MARGIN = 0.75

    /**
     * Best first. [spread] (0..1, see [spreadSeed]) picks among the servers
     * within 15 % of the best, so the phones that see the same free node do
     * not all land on it at once.
     */
    fun rank(candidates: List<SmartCandidate>, advice: ProtocolAdvice?, spread: Double? = null): List<RankedServer> {
        val reachable = candidates.filter { it.latencyMs != null || !it.silenceMeansDown }
        val allowed = reachable.filter { it.verdict != ProtocolVerdict.BLOCKED }
        // Everything blocked or silent: better to try something than nothing.
        val pool = allowed.ifEmpty { reachable }.ifEmpty { candidates }
        val ranked = pool.filterNot(SmartCandidate::full).ifEmpty { pool }
            .map { RankedServer(it.id, it.protocol, score(it, advice)) }
            .sortedWith(compareBy<RankedServer> { it.score }.thenBy { it.id })
        return if (spread == null) ranked else spreadTop(ranked, spread)
    }

    fun score(candidate: SmartCandidate, advice: ProtocolAdvice?): Double {
        val latency = (candidate.latencyMs ?: UNKNOWN_LATENCY_MS).coerceAtLeast(1L).toDouble()
        val history = when (candidate.verdict) {
            ProtocolVerdict.WORKS -> 0.8
            ProtocolVerdict.UNKNOWN -> 1.0
            ProtocolVerdict.SUSPECT -> 2.0
            ProtocolVerdict.BLOCKED -> 10.0
        }
        val operator = when {
            // The phone's own experience outweighs the operator average.
            candidate.verdict == ProtocolVerdict.WORKS || advice == null -> 1.0
            candidate.protocol in advice.avoid -> 3.0
            candidate.protocol in advice.preferred -> 0.85
            else -> 1.0
        }
        val quality = candidate.quality?.takeIf(Double::isFinite)?.coerceIn(MIN_QUALITY, MAX_QUALITY) ?: 1.0
        return latency * loadFactor(candidate.load) * history * operator * quality
    }

    /**
     * A node's share of a busy uplink shrinks like a queue: half full doubles
     * the cost, 80 % makes it five times, a full one twenty times.
     */
    fun loadFactor(load: Double?): Double = 1.0 / (1.0 - (load ?: UNKNOWN_LOAD).coerceIn(0.0, MAX_LOAD))

    /** An old reading fades into [UNKNOWN_LOAD]: the node may have filled up or emptied since. */
    fun agedLoad(load: Double?, ageMs: Long): Double? {
        if (load == null || ageMs >= LOAD_FORGET_MS) return null
        val trust = if (ageMs <= LOAD_TRUST_MS) 1.0 else 1.0 - (ageMs - LOAD_TRUST_MS).toDouble() / (LOAD_FORGET_MS - LOAD_TRUST_MS)
        return trust * load + (1.0 - trust) * UNKNOWN_LOAD
    }

    /**
     * The same for ten minutes on this phone and different on the next one:
     * a random salt that never leaves the phone and the current ten minutes.
     */
    fun spreadSeed(salt: String, nowMs: Long): Double {
        val bucket = Math.floorDiv(nowMs, SPREAD_BUCKET_MS)
        val digest = MessageDigest.getInstance("SHA-256").digest("levik-spread-v1|$salt|$bucket".toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest, 0, 4).int.toLong() and 0xffff_ffffL).toDouble() / 0x1_0000_0000L.toDouble()
    }

    /** Moves one of the near-best servers to the front, the better ones more often. */
    fun spreadTop(ranked: List<RankedServer>, spread: Double): List<RankedServer> {
        val best = ranked.firstOrNull() ?: return emptyList()
        val close = ranked.take(SPREAD_MAX).filter { it.score <= best.score * SPREAD_MARGIN }
        if (close.size < 2) return ranked
        val weights = close.map { 1.0 / it.score.coerceAtLeast(1e-9) }
        var target = spread.coerceIn(0.0, 0.999_999) * weights.sum()
        var chosen = close.last()
        for ((index, item) in close.withIndex()) {
            target -= weights[index]
            if (target < 0) {
                chosen = item
                break
            }
        }
        return listOf(chosen) + ranked.filter { it !== chosen }
    }

    /**
     * Stage 4, docs/app-platform.md "Smart Auto": a running automatic
     * connection leaves a node only when it has been full for five minutes by
     * fresh readings, the connection is idle (nothing is cut), the last such
     * move was half an hour ago and a clearly better server has room.
     */
    fun overloadMoveDue(
        fullSinceMs: Long?,
        loadAgeMs: Long,
        idle: Boolean,
        lastMoveAtMs: Long?,
        currentScore: Double?,
        bestScore: Double?,
        nowMs: Long,
    ): Boolean {
        if (fullSinceMs == null || nowMs - fullSinceMs < OVERLOAD_CONFIRM_MS || loadAgeMs > LOAD_TRUST_MS + 60_000L) return false
        if (!idle || (lastMoveAtMs != null && nowMs - lastMoveAtMs < OVERLOAD_MOVE_COOLDOWN_MS)) return false
        if (currentScore == null || bestScore == null) return false
        return bestScore < currentScore * OVERLOAD_MOVE_MARGIN
    }

    /**
     * The next rung after a failed traffic check: the best server of a
     * protocol not tried yet in this attempt, else the best untried server.
     */
    fun nextRung(ranked: List<RankedServer>, triedProtocols: Set<String>, triedIds: Set<String>): RankedServer? =
        ranked.firstOrNull { it.protocol !in triedProtocols && it.id !in triedIds }
            ?: ranked.firstOrNull { it.id !in triedIds }

    /**
     * Under allowlists: signed-in relays first, then the ordinary mobile
     * servers, then the other relays, then the mobile servers that just failed
     * here. An ordinary mobile server is reached through a CDN, so its ping
     * measures the CDN's edge, not the tunnel behind it: on Beeline a fast
     * ping hid a second of delay in the tunnel. The ping cannot tell it
     * from a relay, so a relay the user signed in to goes first. Within each
     * group the server that last carried traffic here goes first, then those
     * that answered a ping, by score; a silent one may still pass the
     * allowlist, so it is tried rather than left out. A relay is never pinged:
     * it is ranked by the score alone, with [UNKNOWN_LATENCY_MS] for its
     * latency.
     */
    fun rankAllowlist(candidates: List<AllowlistCandidate>): List<RankedServer> =
        candidates
            .map { item -> item to score(item.candidate, advice = null) }
            .sortedWith(
                compareBy<Pair<AllowlistCandidate, Double>>(
                    { (item, _) -> allowlistTier(item) },
                    { (item, _) -> !item.lastWinner },
                    { (item, _) -> item.candidate.latencyMs == null && !item.relay },
                    { (_, score) -> score },
                    { (item, _) -> item.candidate.id },
                ),
            )
            .map { (item, score) -> RankedServer(item.candidate.id, item.candidate.protocol, score) }

    private fun allowlistTier(item: AllowlistCandidate): Int = when {
        item.failedRecently -> 3
        item.relay && item.signedIn -> 0
        item.relay -> 2
        else -> 1
    }

    /** Moving a running connection costs a reconnect; only a clearly better server is worth it. */
    fun worthSwitching(currentScore: Double?, bestScore: Double): Boolean =
        currentScore == null || bestScore < currentScore * SWITCH_MARGIN
}
