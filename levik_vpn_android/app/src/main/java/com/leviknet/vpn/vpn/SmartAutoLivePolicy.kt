package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.SmartRouteResource

/** Confirmation and cooldown prevent a noisy background sample from disturbing a good route. */
internal class SmartAutoLivePolicy {
    private var challenger: String? = null
    private var wins = 0
    private var failures = 0
    private var fullRounds = 0
    private var lastMoveMs: Long? = null
    val pendingId: String? get() = challenger

    fun consider(current: String, measurements: Map<String, SmartRouteMeasurement>, resources: Map<String, SmartRouteResource>, nowMs: Long): String? {
        val incumbent = measurements[current]
        failures = if (incumbent != null && incumbent.samplesMs.isEmpty()) failures + 1 else 0
        fullRounds = if (resources[current]?.overloaded == true) fullRounds + 1 else 0
        val emergency = failures >= 2 || fullRounds >= 2
        val candidates = measurements.filter { (id, value) -> id != current && value.confirmed && resources[id]?.overloaded != true }
        val best = SmartRouteSelection.order(candidates, resources).firstOrNull()
        val improvement = best?.let { id -> incumbent?.let { better(it, candidates.getValue(id)) } } == true
        val suspected = failures > 0 || fullRounds > 0
        if (best == null || !suspected && !improvement) { challenger = null; wins = 0; return null }
        if (challenger == best) wins++ else { challenger = best; wins = 1 }
        if (wins < 2 || !emergency && (!improvement || lastMoveMs?.let { nowMs - it < COOLDOWN_MS } == true)) return null
        return best
    }

    fun installed(nowMs: Long) { lastMoveMs = nowMs; challenger = null; wins = 0; failures = 0; fullRounds = 0 }
    fun pause() { challenger = null; wins = 0; failures = 0; fullRounds = 0 }

    private fun better(current: SmartRouteMeasurement, next: SmartRouteMeasurement): Boolean {
        if (!current.confirmed || !current.delayCost.isFinite()) return false
        val latency = next.delayCost <= current.delayCost * 0.8 && current.delayCost - next.delayCost >= 10.0
        val bandwidth = if (current.hasBandwidth && next.hasBandwidth) {
            val maxDown = maxOf(requireNotNull(current.downloadMbps), requireNotNull(next.downloadMbps))
            val maxUp = maxOf(requireNotNull(current.uploadMbps), requireNotNull(next.uploadMbps))
            val oldFitness = minOf(requireNotNull(current.downloadMbps) / maxDown, requireNotNull(current.uploadMbps) / maxUp)
            val newFitness = minOf(requireNotNull(next.downloadMbps) / maxDown, requireNotNull(next.uploadMbps) / maxUp)
            newFitness >= 1.25 * oldFitness && next.delayCost <= current.delayCost * 1.1 + 10.0
        } else false
        return latency || bandwidth
    }

    companion object { const val COOLDOWN_MS = 5 * 60_000L }
}
