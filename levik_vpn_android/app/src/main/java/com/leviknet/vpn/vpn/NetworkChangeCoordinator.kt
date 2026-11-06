package com.leviknet.vpn.vpn

import kotlinx.coroutines.Job

/** A retained cellular carrier is not the target when returning to Wi-Fi. */
internal fun <T> networkHandoverTarget(selected: T?, preferred: T?, returningToRegular: Boolean): T? =
    if (returningToRegular) preferred else selected

/** Capability/link callbacks for one target share work; only the latest physical target owns it. */
internal class NetworkChangeCoordinator<T> {
    private var revision = 0L
    private var request: Pair<Long, T>? = null
    private var job: Job? = null

    @Synchronized fun begin(generation: Long, target: T): Long? {
        if (request == (generation to target) && job?.isCompleted != true) return null
        request = generation to target
        revision++
        job?.cancel()
        job = null
        return revision
    }

    @Synchronized fun current(value: Long): Boolean = value == revision && request != null

    @Synchronized fun track(value: Long, next: Job) {
        if (current(value)) job = next else next.cancel()
    }

    @Synchronized fun reset() {
        revision++
        request = null
        job?.cancel()
        job = null
    }
}
