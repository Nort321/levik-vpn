package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.ProtocolAdvice

/** What Smart Auto knows about one server before connecting. */
data class SmartCandidate(
    val id: String,
    /** The protocol's telemetry name: VLESS Reality and VLESS XHTTP are different rungs. */
    val protocol: String,
    /** Time to reach the server; null when it did not answer. */
    val latencyMs: Long?,
    /** True when no answer means the server is down (TCP); a UDP server may just not answer probes. */
    val silenceMeansDown: Boolean,
    /** 0 (idle) to 1 (full); null when the website did not say. */
    val load: Double?,
    val verdict: ProtocolVerdict,
)

data class RankedServer(val id: String, val protocol: String, val score: Double)

/** A mobile server under allowlists, docs/app-platform.md "Allowlists". */
data class AllowlistCandidate(
    val candidate: SmartCandidate,
    /** WDTT or Yandex: slow by design, so tried only after the ordinary mobile servers. */
    val relay: Boolean,
    /** An ordinary mobile server that carried nothing under this network's allowlists a moment ago. */
    val failedRecently: Boolean,
)

/**
 * Smart Auto, docs/app-platform.md "Smart Auto": the best server is the one
 * that carries traffic on this network, answers fast and is not busy.
 *
 * score = latency × (1 + 2 × load) × history × advice; lower is better.
 * Protocols the phone saw blocked here are left out; the operator advice
 * from the website only counts until the phone has its own experience.
 */
object SmartServerRanking {
    /** Assumed for a UDP server that does not answer probes (obfuscated or filtered). */
    const val UNKNOWN_LATENCY_MS = 400L

    /** Assumed when the website did not report the load. */
    const val UNKNOWN_LOAD = 0.35
    private const val LOAD_WEIGHT = 2.0
    private const val OVERLOADED = 0.9
    private const val OVERLOAD_FACTOR = 2.0

    /** A running connection moves only to a server this much better. */
    const val SWITCH_MARGIN = 0.75

    fun rank(candidates: List<SmartCandidate>, advice: ProtocolAdvice?): List<RankedServer> {
        val reachable = candidates.filter { it.latencyMs != null || !it.silenceMeansDown }
        val allowed = reachable.filter { it.verdict != ProtocolVerdict.BLOCKED }
        // Everything blocked or silent: better to try something than nothing.
        val pool = allowed.ifEmpty { reachable }.ifEmpty { candidates }
        return pool
            .map { RankedServer(it.id, it.protocol, score(it, advice)) }
            .sortedWith(compareBy<RankedServer> { it.score }.thenBy { it.id })
    }

    fun score(candidate: SmartCandidate, advice: ProtocolAdvice?): Double {
        val latency = (candidate.latencyMs ?: UNKNOWN_LATENCY_MS).coerceAtLeast(1L).toDouble()
        val load = (candidate.load ?: UNKNOWN_LOAD).coerceIn(0.0, 1.0)
        val loadFactor = (1.0 + LOAD_WEIGHT * load) * if (load >= OVERLOADED) OVERLOAD_FACTOR else 1.0
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
        return latency * loadFactor * history * operator
    }

    /**
     * The next rung after a failed traffic check: the best server of a
     * protocol not tried yet in this attempt, else the best untried server.
     */
    fun nextRung(ranked: List<RankedServer>, triedProtocols: Set<String>, triedIds: Set<String>): RankedServer? =
        ranked.firstOrNull { it.protocol !in triedProtocols && it.id !in triedIds }
            ?: ranked.firstOrNull { it.id !in triedIds }

    /**
     * Under allowlists: ordinary mobile servers first, then relays, then the
     * mobile servers that just failed here. Within each, the servers that
     * answered a ping go first, by score; a silent one may still pass the
     * allowlist, so it is tried rather than left out.
     */
    fun rankAllowlist(candidates: List<AllowlistCandidate>): List<RankedServer> =
        candidates
            .map { item -> item to score(item.candidate, advice = null) }
            .sortedWith(
                compareBy<Pair<AllowlistCandidate, Double>>(
                    { (item, _) -> allowlistTier(item) },
                    { (item, _) -> item.candidate.latencyMs == null },
                    { (_, score) -> score },
                    { (item, _) -> item.candidate.id },
                ),
            )
            .map { (item, score) -> RankedServer(item.candidate.id, item.candidate.protocol, score) }

    private fun allowlistTier(item: AllowlistCandidate): Int = when {
        item.relay -> 1
        item.failedRecently -> 2
        else -> 0
    }

    /** Moving a running connection costs a reconnect; only a clearly better server is worth it. */
    fun worthSwitching(currentScore: Double?, bestScore: Double): Boolean =
        currentScore == null || bestScore < currentScore * SWITCH_MARGIN
}
