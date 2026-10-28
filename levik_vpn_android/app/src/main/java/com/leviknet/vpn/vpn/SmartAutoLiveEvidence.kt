package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.platform.SmartRouteResource

/** Evidence belongs to one live core lease and one physical route identity. Missing metadata is not a new route. */
internal class SmartAutoLiveEvidence(initial: Map<String, SmartRouteMeasurement>, routes: Map<String, SmartRouteResource>, nowMs: Long) {
    private data class Entry(val routeId: String?, val bandwidth: SmartRouteMeasurement? = null, val bandwidthAt: Long? = null, val proofAt: Long? = null)
    private val entries = (initial.keys + routes.keys).associateWith { id ->
        val value = initial[id]
        Entry(routes[id]?.id, value?.takeIf { it.hasBandwidth }, nowMs.takeIf { value?.hasBandwidth == true },
            nowMs.takeIf { value?.hasBandwidth == true || (value?.screeningMbps ?: 0.0) > 0.0 })
    }.toMutableMap()

    @Synchronized fun refresh(routes: Map<String, SmartRouteResource>): Set<String> {
        val changed = mutableSetOf<String>()
        routes.forEach { (id, route) ->
            val previous = entries[id]
            if (previous?.routeId != route.id) { changed += id; entries[id] = Entry(route.id) }
        }
        return changed
    }
    @Synchronized fun routeIds(): Map<String, String?> = entries.mapValues { it.value.routeId }
    @Synchronized fun bandwidth(id: String, nowMs: Long): SmartRouteMeasurement? = entries[id]?.takeIf {
        it.bandwidthAt?.let { at -> nowMs - at in 0 until TTL_MS } == true
    }?.bandwidth
    @Synchronized fun proven(id: String, nowMs: Long): Boolean = entries[id]?.proofAt?.let { nowMs - it in 0 until TTL_MS } == true
    @Synchronized fun measuredBandwidth(id: String, value: SmartRouteMeasurement, nowMs: Long) {
        if (value.hasBandwidth) entries[id] = (entries[id] ?: Entry(null)).copy(bandwidth = value, bandwidthAt = nowMs, proofAt = nowMs)
    }
    @Synchronized fun verified(id: String, nowMs: Long) { entries[id] = (entries[id] ?: Entry(null)).copy(proofAt = nowMs) }
    @Synchronized fun failed(id: String) { entries[id]?.let { entries[id] = Entry(it.routeId) } }

    companion object { const val TTL_MS = 5 * 60_000L }
}
