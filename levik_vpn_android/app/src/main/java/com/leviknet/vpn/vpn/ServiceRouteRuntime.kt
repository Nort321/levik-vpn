package com.leviknet.vpn.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class ServiceRouteReport(
    val revision: Long,
    val fingerprint: String,
    val state: String,
    val routes: List<ServiceRouteState>,
    val detail: String? = null,
)

@Serializable
data class ServiceRouteState(val id: String, val state: String, val exitId: String?)

/** Owns only balancer targets. A failed country never invokes tunnel shutdown or Kill Switch. */
internal class ServiceRouteRuntime(
    private val config: ServiceRouteConfig,
    private val scope: CoroutineScope,
    private val report: suspend (ServiceRouteReport) -> Unit,
) : AutoCloseable {
    private val control = ServiceRouteControl(config)
    private val selection = ServiceRouteSelection(config.policy, config.mainExitId)
    private var job: Job? = null
    private var previousReport: ServiceRouteReport? = null
    private var lastReportAtNanos = 0L
    private val currentTargets = mutableMapOf<String, String>()
    private val health = config.candidates.associate { it.exitId to ServiceExitHealth() }
    private var currentMainExitId = config.mainExitId
    private val closed = AtomicBoolean(false)

    fun start() {
        check(job == null)
        job = scope.launch(Dispatchers.IO) {
            // Strict service routes start at block; the ordinary main route remains usable if API fails.
            var ready = false
            repeat(10) {
                if (!ready) {
                    currentCoroutineContext().ensureActive()
                    check(!closed.get())
                    val initialMain = if (config.policy.exits.first { it.exitId == config.mainExitId }.state == "enabled")
                        config.candidates.first { it.exitId == config.mainExitId }.outboundTag else ServiceRouteConfig.BLOCK
                    ready = runCatching { control.override(ServiceRouteConfig.MAIN, initialMain) }.isSuccess
                    if (!ready) delay(100)
                }
            }
            if (!ready) {
                send("error", emptyList(), "control_unavailable")
                return@launch
            }
            while (isActive) {
                try {
                    val limiter = Semaphore(4)
                    config.candidates.filter { candidate -> config.policy.exits.any { it.exitId == candidate.exitId && it.state == "enabled" } }.map { candidate -> async {
                        limiter.withPermit { candidate.exitId to probeServiceExit(candidate) }
                    } }.awaitAll().forEach { (exitId, success) -> health.getValue(exitId).observe(success) }
                    val alive = health.filterValues { it.alive }.keys
                    val initialMainUsable = currentMainExitId == config.mainExitId &&
                        config.policy.exits.first { it.exitId == config.mainExitId }.state == "enabled" &&
                        !health.getValue(config.mainExitId).wasConfirmedDown
                    if (currentMainExitId !in alive && !initialMainUsable) {
                        val mainCountry = config.policy.exits.first { it.exitId == config.mainExitId }.countryCode
                        currentMainExitId = config.mainExitId.takeIf { it in alive }
                            ?: if (config.allowMainFailover) config.policy.exits.filter { it.exitId in alive }
                                .sortedWith(compareBy({ it.countryCode != mainCountry }, { it.exitId })).firstOrNull()?.exitId ?: config.mainExitId
                            else config.mainExitId
                    }
                    selection.mainExitId = currentMainExitId
                    val mainTarget = config.candidates.firstOrNull { it.exitId == currentMainExitId && (it.exitId in alive || initialMainUsable) }?.outboundTag
                        ?: ServiceRouteConfig.BLOCK
                    setTarget(ServiceRouteConfig.MAIN, mainTarget)
                    val states = config.policy.rules.mapIndexed { index, rule ->
                        val decision = selection.select(rule, alive)
                        setTarget(ServiceRouteConfig.routeTag(index), config.tagFor(decision))
                        ServiceRouteState(rule.id, decision.state, decision.exitId)
                    }
                    send("applied", states)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Existing targets stay installed on API failure; no stop/restart of unrelated traffic.
                    send("error", previousReport?.routes.orEmpty(), "control_update_failed")
                }
                delay(10_000)
            }
        }
    }

    private suspend fun setTarget(balancer: String, target: String) {
        currentCoroutineContext().ensureActive()
        check(!closed.get())
        if (currentTargets[balancer] == target) return
        control.override(balancer, target)
        currentTargets[balancer] = target
    }

    private suspend fun send(state: String, states: List<ServiceRouteState>, detail: String? = null) {
        currentCoroutineContext().ensureActive()
        if (closed.get()) return
        val next = ServiceRouteReport(config.policy.revision, config.policy.fingerprint, state, states, detail)
        val now = System.nanoTime()
        if (next == previousReport && now - lastReportAtNanos < 90_000_000_000L) return
        try {
            report(next)
            previousReport = next
            lastReportAtNanos = now
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The next round retries the receipt. A control-plane outage never changes data-plane targets.
        }
    }

    override fun close() {
        closed.set(true)
        job?.cancel()
        job = null
        control.close()
    }

}

/** Three failed rounds declare an exit down; recovery needs two successful rounds. */
internal class ServiceExitHealth {
    var alive = false
        private set
    private var successes = 0
    private var failures = 0
    private var seenHealthy = false
    val confirmedDown: Boolean get() = failures >= 3
    var wasConfirmedDown = false
        private set

    fun observe(success: Boolean) {
        if (success) {
            failures = 0
            successes++
            if ((!seenHealthy && !wasConfirmedDown) || successes >= 2) { alive = true; seenHealthy = true }
        } else {
            successes = 0
            failures++
            if (failures >= 3) { alive = false; wasConfirmedDown = true }
        }
    }
}
