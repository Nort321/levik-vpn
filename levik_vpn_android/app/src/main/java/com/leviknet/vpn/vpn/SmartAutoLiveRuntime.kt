package com.leviknet.vpn.vpn

import android.net.Network
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
    private val enabled: () -> Boolean,
    private val currentId: () -> String?,
    private val heavyTraffic: () -> Boolean,
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

    fun start() {
        check(job == null)
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive && !closed.get() && enabled()) {
                refreshActivity()
                delay(5_000L)
            }
        }
        job = scope.launch(Dispatchers.IO) {
            delay(30_000L)
            while (isActive && !closed.get()) {
                if (!enabled()) break
                try { compare() } catch (error: CancellationException) { throw error } catch (_: Exception) {
                    // The incumbent and every established stream stay installed on measurement/control failure.
                }
                withTimeoutOrNull(30_000L) { wake.receive() }
            }
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
            SmartRouteProber(candidates).use { prober ->
                prober.measureLatency(light = true, onMeasurement = ::observe)
                if (!heavyTraffic() && now - lastSpeedMs >= 5 * 60_000L && refreshActivity()) {
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
        val currentHealth = fresh[current]
        if (currentHealth?.samplesMs?.isEmpty() == true || current in dataFailed) { failedHealthRounds++; goodHealthRounds = 0 }
        else if (currentHealth?.confirmed == true) { goodHealthRounds++; failedHealthRounds = 0 }
        if (failedHealthRounds >= 2) mainHealth(false) else if (goodHealthRounds >= 2) mainHealth(true)
        if (!refreshActivity()) { policy.pause(); return }
        val selection = if (current in dataFailed) fresh + (current to (fresh[current] ?: SmartRouteMeasurement(emptyList(), 1)).copy(samplesMs = emptyList())) else fresh
        val winner = policy.consider(current, selection, smartAuto.routeResources(valid), System.currentTimeMillis()) ?: return
        val candidate = config.candidates.first { it.id == winner }
        if (!evidence.proven(winner, System.currentTimeMillis())) {
            val verified = withTimeoutOrNull(4_000L) { SmartRouteProber(listOf(candidate)).use { it.verifyData(winner) } } == true
            if (!verified) { evidence.failed(winner); policy.pause(); return }
            evidence.verified(winner, System.currentTimeMillis())
            dataFailed.remove(winner)
        }
        val canInstall: suspend () -> Boolean = {
            if (!refreshActivity()) false else {
                val latest = smartAuto.routeResources(valid)
                currentId() == current && listOf(current, winner).all { id ->
                    latest[id]?.id.let { it == null || it == roundRoutes[id] } &&
                        valid.firstOrNull { it.id == id }?.let { contexts[id] == smartAuto.measurementContext(it) } == true
                }
            }
        }
        if (enabled() && install(candidate, control, canInstall)) {
            policy.installed(System.currentTimeMillis()); failedHealthRounds = 0; goodHealthRounds = 0
        }
    }

    override fun close() {
        closed.set(true)
        job?.cancel()
        statsJob?.cancel()
        control?.close()
    }
}
