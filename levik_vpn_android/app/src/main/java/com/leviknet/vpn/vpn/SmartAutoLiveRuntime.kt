package com.leviknet.vpn.vpn

import android.net.Network
import com.leviknet.vpn.core.logger.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** Compares live outbound paths. Installing a winner only changes the target for new core flows. */
internal class SmartAutoLiveRuntime(
    private val config: SmartAutoLiveConfig,
    private val scope: CoroutineScope,
    private val network: Network,
    private val smartAuto: SmartAuto,
    /** Compare a small diverse shortlist after the connection is already up. */
    private val initialRound: Boolean = false,
    private val enabled: () -> Boolean,
    private val currentId: () -> String?,
    private val heavyTraffic: () -> Boolean,
    private val metered: () -> Boolean = { false },
    private val probeTargets: () -> List<String> = { emptyList() },
    private val onDataVerified: () -> Unit = {},
    private val refreshLoad: suspend () -> Unit,
    private val readTraffic: suspend (ServiceRouteControl?) -> SmartAutoTrafficCounters?,
    private val mainHealth: suspend (Boolean) -> Unit,
    private val install: suspend (SmartRouteProbeConfig.Candidate, ServiceRouteControl?, suspend () -> Boolean) -> Boolean,
) : AutoCloseable {
    private val control = if (config.ownsControl) ServiceRouteControl(config) else null
    private val policy = SmartAutoLivePolicy()
    private val contexts = config.originals.associate { it.id to smartAuto.measurementContext(it) }
    private val evidence = SmartAutoLiveEvidence(smartAuto.routeMeasurements(config.originals, network), smartAuto.routeResources(config.originals), System.currentTimeMillis())
    private val dataFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null
    private var statsJob: Job? = null
    private val activity = SmartAutoActivityGuard()
    private var cursor = 0
    private var lastSpeedMs = System.currentTimeMillis()
    private var failedHealthRounds = 0
    private var goodHealthRounds = 0
    private var rememberedId: String? = null
    private var rememberedAtMs = 0L

    fun start() {
        check(job == null)
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive && !closed.get() && enabled()) {
                refreshActivity()
                delay(5_000L)
            }
        }
        job = scope.launch(Dispatchers.IO) {
            try { if (initialRound) measureAll() else compare() } catch (error: CancellationException) { throw error } catch (_: Exception) {
                // The incumbent stays installed; the regular rounds below keep comparing.
            }
            waitForNextRound()
            while (isActive && !closed.get()) {
                if (!enabled()) break
                try { compare() } catch (error: CancellationException) { throw error } catch (_: Exception) {
                    // The incumbent and every established stream stay installed on measurement/control failure.
                }
                waitForNextRound()
            }
        }
    }


    private suspend fun waitForNextRound() {
        val next = SmartAutoProbeSchedule.intervalMs(
            failedHealthRounds, policy.needsFastRecheck || currentId()?.let(dataFailed::contains) == true,
            policy.pendingId != null, metered(),
        )
        withTimeoutOrNull(next) { wake.receive() }
    }

    private fun prober(candidates: List<SmartRouteProbeConfig.Candidate>) =
        SmartRouteProber(candidates, targets = probeTargets())

    private fun rememberSuccess(id: String) {
        val now = System.currentTimeMillis()
        if (rememberedId == id && now - rememberedAtMs in 0 until 60_000L) return
        config.originals.firstOrNull { it.id == id }?.let {
            // Verification suspends: never stamp old-core evidence with a newly changed profile/path.
            if (!enabled() || contexts[id] != smartAuto.measurementContext(it) ||
                smartAuto.routeResources(listOf(it))[id]?.id != evidence.routeIds()[id]
            ) return
            smartAuto.recordSuccess(it, network)
            rememberedId = id
            rememberedAtMs = now
        }
    }

    private suspend fun confirmCurrent(current: String, measured: SmartRouteMeasurement?) {
        if (measured?.confirmed != true) return
        val source = config.originals.firstOrNull { it.id == current } ?: return
        val expectedRoute = evidence.routeIds()[current]
        fun stillCurrent() = enabled() && currentId() == current &&
            contexts[current] == smartAuto.measurementContext(source) &&
            smartAuto.routeResources(listOf(source))[current]?.id == expectedRoute
        if (!stillCurrent()) return
        if (!evidence.proven(current, System.currentTimeMillis()) || current in dataFailed) {
            val candidate = config.candidates.firstOrNull { it.id == current } ?: return
            val verified = withTimeoutOrNull(5_500L) { prober(listOf(candidate)).use { it.verifyData(current, measured.successfulTargets) } } == true
            if (!stillCurrent()) return
            if (!verified) { evidence.failed(current); dataFailed += current; forgetSuccess(current); return }
            evidence.verified(current, System.currentTimeMillis())
            AppLogger.i(TAG, "Current route verified: 65536 bytes through VPN")
            onDataVerified()
        }
        dataFailed.remove(current)
        rememberSuccess(current)
    }

    /** TUN send-without-receive is a reason to check promptly, not proof of a bad route. */
    fun checkSoon() {
        // Small RTT responses cannot disprove throttling that starts after a few KiB.
        currentId()?.let(evidence::failed)
        wake.trySend(Unit)
    }

    private fun forgetSuccess(id: String) {
        smartAuto.forgetStartupWinner(id, network)
        if (rememberedId == id) rememberedId = null
        val server = config.originals.firstOrNull { it.id == id } ?: return
        if (contexts[id] == smartAuto.measurementContext(server)) {
            smartAuto.recordRouteMeasurement(server, network, SmartRouteMeasurement(emptyList(), 2),
                contexts.getValue(id), smartAuto.routeResources(listOf(server))[id]?.id)
        }
    }

    fun trafficFailed(id: String) { evidence.failed(id); dataFailed += id; wake.trySend(Unit) }

    private suspend fun refreshActivity(): Boolean {
        val counters = try { readTraffic(control) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        val now = android.os.SystemClock.elapsedRealtime()
        activity.observe(counters, now)
        return enabled() && activity.allowed(now)
    }

    private suspend fun compare() {
        refreshLoad()
        currentCoroutineContext().ensureActive()
        if (!enabled()) return
        val current = currentId() ?: return
        // The core's candidate/tag set is immutable. A changed profile/settings needs the normal reconnect path.
        val valid = config.originals.filter { contexts[it.id] == smartAuto.measurementContext(it) }
        if (valid.none { it.id == current }) return
        val resources = smartAuto.routeResources(valid)
        if (evidence.refresh(resources).isNotEmpty()) policy.pause()
        val roundRoutes = evidence.routeIds()
        val alternatives = valid.filter { it.id != current && resources[it.id]?.overloaded != true }
        val sampled = linkedSetOf(current)
        policy.pendingId?.takeIf { pending -> alternatives.any { it.id == pending } }?.let(sampled::add)
        if (alternatives.isNotEmpty()) repeat(alternatives.size) {
            if (sampled.size < 3) sampled += alternatives[(cursor + it) % alternatives.size].id
        }
        cursor = if (alternatives.isEmpty()) 0 else (cursor + 2) % alternatives.size
        val candidates = config.candidates.filter { it.id in sampled }
        val measured = java.util.concurrent.ConcurrentHashMap<String, SmartRouteMeasurement>()
        val now = System.currentTimeMillis()
        fun observe(id: String, measurement: SmartRouteMeasurement) {
            val previous = evidence.bandwidth(id, now)
            val value = if (!measurement.hasBandwidth && previous != null) measurement.copy(
                downloadMbps = previous.downloadMbps, uploadMbps = previous.uploadMbps,
            ) else measurement
            measured[id] = value
        }
        withTimeoutOrNull(12_000L) {
            prober(candidates).use { prober ->
                prober.measureLatency(light = true, rounds = 4, onMeasurement = ::observe)
                if (!metered() && !heavyTraffic() && current !in dataFailed && now - lastSpeedMs >= 5 * 60_000L && refreshActivity()) {
                    lastSpeedMs = now
                    val finalists = measured.filterValues { it.confirmed }.toMap()
                    prober.measureBandwidth(finalists) { id, value ->
                        if (value.hasBandwidth) { evidence.measuredBandwidth(id, value, now); dataFailed.remove(id) }
                        observe(id, value)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (!enabled() || currentId() != current) return
        val latestResources = smartAuto.routeResources(valid)
        val changed = evidence.refresh(latestResources)
        if (changed.isNotEmpty()) policy.pause()
        val fresh = measured.filterKeys { id -> id !in changed && valid.firstOrNull { it.id == id }?.let { contexts[id] == smartAuto.measurementContext(it) } == true }
        fresh.forEach { (id, value) -> valid.firstOrNull { it.id == id }?.let { smartAuto.recordRouteMeasurement(it, network, value, contexts.getValue(id), roundRoutes[id]) } }
        if (current in changed) return
        confirmCurrent(current, fresh[current])
        if (!enabled() || currentId() != current) return
        val currentHealth = fresh[current]
        failedHealthRounds = SmartAutoProbeSchedule.failedRounds(failedHealthRounds, currentHealth, current in dataFailed)
        goodHealthRounds = if (currentHealth?.confirmed == true && current !in dataFailed) goodHealthRounds + 1 else 0
        if (failedHealthRounds >= 2) { forgetSuccess(current); mainHealth(false) }
        else if (goodHealthRounds >= 2) mainHealth(true)
        val optimizationAllowed = refreshActivity()
        val selection = if (current in dataFailed) fresh + (current to (fresh[current] ?: SmartRouteMeasurement(emptyList(), 1)).copy(samplesMs = emptyList())) else fresh
        val winner = policy.consider(current, selection, smartAuto.routeResources(valid), System.currentTimeMillis(), optimizationAllowed, current in dataFailed) ?: return
        val recovering = policy.recovering
        val candidate = config.candidates.first { it.id == winner }
        if (!evidence.proven(winner, System.currentTimeMillis())) {
            val verified = withTimeoutOrNull(5_500L) { prober(listOf(candidate)).use { it.verifyData(winner, fresh[winner]?.successfulTargets.orEmpty()) } } == true
            if (!verified) { evidence.failed(winner); policy.pause(); return }
            evidence.verified(winner, System.currentTimeMillis())
            dataFailed.remove(winner)
        }
        val canInstall: suspend () -> Boolean = {
            if (!recovering && !refreshActivity()) false else {
                val latest = smartAuto.routeResources(valid)
                currentId() == current && latest[winner]?.overloaded != true && listOf(current, winner).all { id ->
                    latest[id]?.id.let { it == null || it == roundRoutes[id] } &&
                        valid.firstOrNull { it.id == id }?.let { contexts[id] == smartAuto.measurementContext(it) } == true
                }
            }
        }
        if (enabled() && install(candidate, control, canInstall)) {
            policy.installed(System.currentTimeMillis()); failedHealthRounds = 0; goodHealthRounds = 0
            rememberSuccess(winner)
        }
    }

    /**
     * A small, diverse first comparison through the running core. Remaining routes rotate through
     * normal rounds; bandwidth transfers wait for an idle, unmetered connection.
     */
    private suspend fun measureAll() {
        refreshLoad()
        currentCoroutineContext().ensureActive()
        if (!enabled()) return
        val current = currentId() ?: return
        val valid = config.originals.filter { contexts[it.id] == smartAuto.measurementContext(it) }
        if (valid.none { it.id == current }) return
        val resources = smartAuto.routeResources(valid)
        evidence.refresh(resources)
        val roundRoutes = evidence.routeIds()
        val ranked = smartAuto.rank(valid, emptyMap(), network).mapNotNull { ranked -> valid.firstOrNull { it.id == ranked.id } }
        val shortlist = SmartRouteSelection.candidates(listOf(valid.first { it.id == current }) + ranked.filter { it.id != current })
            .take(4).mapTo(mutableSetOf()) { it.id }
        val candidates = config.candidates.filter { it.id in shortlist }
        val measured = java.util.concurrent.ConcurrentHashMap<String, SmartRouteMeasurement>()
        AppLogger.d(TAG, "initial measurement begin")
        withTimeoutOrNull(INITIAL_ROUND_MS) {
            prober(candidates).use { prober ->
                prober.measureLatency(light = true, rounds = 4) { id, value -> measured[id] = value }
            }
        }
        AppLogger.d(TAG, "initial measurement end")
        currentCoroutineContext().ensureActive()
        if (!enabled() || currentId() != current) return
        val latestResources = smartAuto.routeResources(valid)
        val changed = evidence.refresh(latestResources)
        val fresh = measured.filterKeys { id -> id !in changed && valid.firstOrNull { it.id == id }?.let { contexts[id] == smartAuto.measurementContext(it) } == true }
        fresh.forEach { (id, value) -> valid.firstOrNull { it.id == id }?.let { smartAuto.recordRouteMeasurement(it, network, value, contexts.getValue(id), roundRoutes[id]) } }
        confirmCurrent(current, fresh[current])
        if (!enabled() || currentId() != current) return
        failedHealthRounds = SmartAutoProbeSchedule.failedRounds(failedHealthRounds, fresh[current], current in dataFailed)
        val winner = policy.initialChoice(current, fresh, latestResources, heavyTraffic(), current in dataFailed) ?: return
        val candidate = config.candidates.first { it.id == winner }
        if (!evidence.proven(winner, System.currentTimeMillis())) {
            val verified = withTimeoutOrNull(5_500L) { prober(listOf(candidate)).use { it.verifyData(winner, fresh[winner]?.successfulTargets.orEmpty()) } } == true
            if (!verified) { evidence.failed(winner); return }
            evidence.verified(winner, System.currentTimeMillis())
        }
        val canInstall: suspend () -> Boolean = {
            val latest = smartAuto.routeResources(valid)
            currentId() == current && latest[winner]?.overloaded != true && listOf(current, winner).all { id ->
                latest[id]?.id.let { it == null || it == roundRoutes[id] } &&
                    valid.firstOrNull { it.id == id }?.let { contexts[id] == smartAuto.measurementContext(it) } == true
            }
        }
        if (enabled() && install(candidate, control, canInstall)) {
            policy.installed(System.currentTimeMillis())
            failedHealthRounds = 0
            rememberSuccess(winner)
        }
    }

    override fun close() {
        closed.set(true)
        job?.cancel()
        statsJob?.cancel()
        control?.close()
    }

    private companion object {
        const val TAG = "SmartAutoLiveRuntime"
        const val INITIAL_ROUND_MS = 6_000L
    }
}
