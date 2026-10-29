package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.SmartRouteResource

/** Measurements use the authenticated VPN outbound, including every cascade hop. */
data class SmartRouteMeasurement(
    val samplesMs: List<Double>,
    val attempts: Int,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val screeningMbps: Double? = null,
    val successfulTargets: Set<String> = emptySet(),
) {
    val delayCost: Double get() {
        val values = samplesMs.filter { it.isFinite() && it > 0 }.sorted()
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        fun medianOf(values: List<Double>): Double = if (values.size % 2 == 0) (values[values.size / 2 - 1] + values[values.size / 2]) / 2 else values[values.size / 2]
        val median = medianOf(values)
        val variability = medianOf(values.map { kotlin.math.abs(it - median) }.sorted())
        val failures = 1.0 - values.size.toDouble() / attempts.coerceAtLeast(values.size)
        return median + variability + failures * 1000.0
    }
    val hasBandwidth: Boolean get() = listOf(downloadMbps, uploadMbps).all { it != null && it.isFinite() && it > 0 }
    val confirmed: Boolean get() = samplesMs.size >= 2 && successfulTargets.size >= 2
    val cacheMs: Long get() = if (confirmed) SmartRouteSelection.CACHE_MS else 10_000L
}

/** A transparent policy: near-best balanced bandwidth, then the most stable low delay. */
object SmartRouteSelection {
    const val BANDWIDTH_MARGIN = 0.90
    const val MAX_CANDIDATES = 32
    const val CACHE_MS = 2 * 60_000L

    fun eligibleIds(ids: Collection<String>, resources: Map<String, SmartRouteResource>, full: Set<String> = emptySet()): Set<String> =
        ids.filter { it !in full && resources[it]?.overloaded != true }.ifEmpty { ids.toList() }.toSet()

    fun select(measurements: Map<String, SmartRouteMeasurement>, resources: Map<String, SmartRouteResource>, candidateIds: Collection<String>, fallbackId: String?): String? {
        order(measurements, resources, candidateIds).firstOrNull()?.let { return it }
        val eligible = eligibleIds(candidateIds, resources)
        return fallbackId?.takeIf(eligible::contains) ?: eligible.firstOrNull()
    }

    fun order(
        measurements: Map<String, SmartRouteMeasurement>,
        resources: Map<String, SmartRouteResource>,
        candidateIds: Collection<String> = measurements.keys,
    ): List<String> {
        val eligible = eligibleIds(candidateIds, resources)
        val passing = measurements.filter { (id, value) -> id in eligible && value.delayCost.isFinite() }
        if (passing.isEmpty()) return emptyList()
        val healthy = passing.filterValues(SmartRouteMeasurement::confirmed).ifEmpty { passing }
        val measured = healthy.filterValues(SmartRouteMeasurement::hasBandwidth)
        fun ordering(values: Map<String, SmartRouteMeasurement>) = compareBy<String> { kotlin.math.floor(values.getValue(it).delayCost / 5.0) }
            .thenBy { id -> resources[id]?.takeIf(SmartRouteResource::fresh)?.let { if (it.state == "degraded") 1 else 0 } ?: 2 }
            .thenByDescending { id -> resources[id]?.takeIf(SmartRouteResource::fresh)?.resourceHeadroom ?: -1.0 }
            .thenBy { values.getValue(it).delayCost }.thenBy { it }
        if (measured.isEmpty()) return healthy.keys.sortedWith(ordering(healthy))
        val down = measured.values.maxOf { requireNotNull(it.downloadMbps) }
        val up = measured.values.maxOf { requireNotNull(it.uploadMbps) }
        val fitness = measured.mapValues { (_, value) -> minOf(requireNotNull(value.downloadMbps) / down, requireNotNull(value.uploadMbps) / up) }
        val floor = fitness.values.max() * BANDWIDTH_MARGIN
        val best = measured.keys.filter { fitness.getValue(it) >= floor }
            .sortedWith(ordering(measured))
        return best + healthy.keys.filterNot(best::contains).sortedBy { healthy.getValue(it).delayCost }
    }

    /** Round-robin topology/protocol diversity; an entry's fast SYN cannot monopolize the probe budget. */
    fun candidates(servers: List<TunnelServer>): List<TunnelServer> {
        val groups = servers.groupBy { it.syncHost() ?: it.id }.values.map { entry ->
            entry.groupBy { it.countryCode to it.smartProtocol() }.values.flatten().toMutableList()
        }
        val result = mutableListOf<TunnelServer>()
        while (result.size < MAX_CANDIDATES && groups.any { it.isNotEmpty() }) {
            for (group in groups) if (group.isNotEmpty() && result.size < MAX_CANDIDATES) result += group.removeAt(0)
        }
        return result
    }
}
