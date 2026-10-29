package com.leviknet.vpn.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal class ServiceRouteRuntimeEffects(
    val write: suspend (String, String) -> Unit,
    val probe: suspend (ServiceRouteConfig.Candidate) -> Boolean,
    val dns: suspend (ServiceRouteConfig.Candidate) -> Boolean,
    val counters: suspend () -> SmartAutoTrafficCounters? = { null },
    val close: () -> Unit = {},
)

/** MAIN belongs to the external owner whenever a live pool is installed. */
internal class ServiceRouteRuntime(
    private val config: ServiceRouteConfig,
    private val scope: CoroutineScope,
    private val automatic: SmartAutoLiveConfig? = null,
    effects: ServiceRouteRuntimeEffects? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val report: suspend (ServiceRouteReport) -> Unit,
) : AutoCloseable {
    private val effects = effects ?: ServiceRouteControl(config, automatic?.candidates?.map { it.tag }?.toSet().orEmpty()).let { wire ->
        ServiceRouteRuntimeEffects(
            write = { balancer, tag -> kotlinx.coroutines.withContext(Dispatchers.IO) { wire.override(balancer, tag) } },
            probe = { candidate -> kotlinx.coroutines.withContext(Dispatchers.IO) { probeServiceExit(candidate) } },
            dns = { candidate -> automatic == null || kotlinx.coroutines.withContext(Dispatchers.IO) { probeHotSwitchDns(candidate.probePath, automatic.dnsResolvers) } },
            counters = { kotlinx.coroutines.withContext(Dispatchers.IO) { wire.tunTrafficCounters() } },
            close = wire::close,
        )
    }
    private val selection = ServiceRouteSelection(config.policy, config.mainExitId)
    private var job: Job? = null
    private var previousReport: ServiceRouteReport? = null
    private var lastReportAtNanos = 0L
    private val currentTargets = mutableMapOf<String, String>()
    private val unconfirmed = mutableSetOf<String>()
    private val states = config.policy.rules.associate { rule -> rule.id to ServiceRouteState(rule.id,
        if (rule.route.kind in setOf("main", "default", "direct")) "active" else "blocked",
        config.mainExitId.takeIf { rule.route.kind == "main" }) }.toMutableMap()
    private val health = mutableMapOf<String, ServiceExitHealth>()
    private val dnsHealth = mutableMapOf<String, ServiceExitHealth>()
    private var currentMainExitId = config.mainExitId
    private val closed = AtomicBoolean(false)
    private val suspended = AtomicBoolean(false)
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val readyGate = CompletableDeferred<Boolean>()
    private val updates = Mutex()
    @Volatile private var automaticMain: Pair<String, String>? = automatic?.let { live ->
        (live.originals.firstOrNull { it.id == live.initial.id }?.let(config.policy::exitFor)?.exitId ?: config.mainExitId) to live.initial.tag
    }
    private var automaticHealthy = true

    init { if (automatic != null) readyGate.complete(true) }

    fun supportsExternalMain(exitId: String, tag: String): Boolean = !closed.get() &&
        automatic?.candidates?.firstOrNull { it.tag == tag }?.let { candidate ->
            automatic.originals.firstOrNull { it.id == candidate.id }?.let(config.policy::exitFor)
                ?.let { it.exitId == exitId && it.state == "enabled" }
        } == true

    suspend fun automaticMainHealth(healthy: Boolean) = updates.withLock {
        if (automatic != null && automaticHealthy != healthy && !closed.get()) {
            automaticHealthy = healthy
            val generation = epoch.get()
            if (!suspended.get()) updateTargets(config.policy.rules.indices.toList(), generation) { true }
        }
    }

    suspend fun tunTrafficCounters(): SmartAutoTrafficCounters? = if (readyGate.await() && !closed.get()) effects.counters() else null
    suspend fun awaitReady(): Boolean = readyGate.await() && !closed.get()

    /** The caller has already acknowledged MAIN. Remember it before suspension,
     * cancellation or waiting on an older policy RPC can interrupt reconciliation. */
    suspend fun commitExternalMain(exitId: String, tag: String, guard: () -> Boolean = { true }) {
        check(supportsExternalMain(exitId, tag)) { "Invalid external main target" }
        val generation = epoch.incrementAndGet()
        automaticMain = exitId to tag
        automaticHealthy = true
        val committed = updates.withLock {
            checkValid(generation, guard)
            val affected = config.policy.rules.indices.filter { index ->
                val rule = config.policy.rules[index]
                rule.route.kind == "main" || rule.route.kind !in setOf("direct", "default") && rule.fallback.kind == "main" || ServiceRouteConfig.routeTag(index) in unconfirmed
            }
            refreshLocked(affected, generation, guard)
        }
        if (!committed) throw CancellationException("Main policy reconciliation cancelled")
        checkValid(generation, guard)
        send("applied", snapshot(), generation = generation)
        checkValid(generation, guard)
    }

    fun setSuspended(value: Boolean) { suspended.set(value); epoch.incrementAndGet() }

    fun start() {
        check(job == null)
        job = scope.launch(dispatcher) {
            if (automatic == null) {
                var ready = false
                repeat(10) {
                    if (!ready) {
                        currentCoroutineContext().ensureActive()
                        check(!closed.get())
                        val initial = if (config.policy.exits.first { it.exitId == config.mainExitId }.state == "enabled")
                            config.candidates.first { it.exitId == config.mainExitId }.outboundTag else ServiceRouteConfig.BLOCK
                        ready = try { effects.write(ServiceRouteConfig.MAIN, initial); true }
                        catch (error: CancellationException) { throw error } catch (_: Exception) { false }
                        if (!ready) delay(100)
                    }
                }
                readyGate.complete(ready)
                if (!ready) { send("error", emptyList(), "control_unavailable"); return@launch }
            }
            while (isActive) {
                val generation = epoch.get()
                try { refresh() }
                catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (generation == epoch.get()) throw error
                }
                catch (_: Exception) {
                    if (generation == epoch.get() && !suspended.get()) send("error", snapshot(), "control_update_failed", generation)
                }
                delay(10_000)
            }
        }
    }

    suspend fun refresh() {
        val generation = epoch.get()
        val applied = updates.withLock {
            if (!valid(generation) { true }) return@withLock false
            refreshLocked(config.policy.rules.indices.toList(), generation) { true }
        }
        if (applied) send("applied", snapshot(), generation = generation)
    }

    private suspend fun refreshLocked(indices: List<Int>, generation: Long, guard: () -> Boolean): Boolean {
        val needed = mutableSetOf<String>()
        fun include(target: ServiceRouteTarget) {
            when (target.kind) {
                "main" -> needed += automaticMain?.first ?: currentMainExitId
                "exit" -> target.exitId?.let(needed::add)
                "country" -> config.policy.exits.filter { it.countryCode == target.countryCode }.forEach { needed += it.exitId }
            }
        }
        indices.forEach { index -> config.policy.rules[index].let {
            if (it.route.kind !in setOf("direct", "default")) { include(it.route); include(it.fallback) }
        } }
        if (automatic == null) needed += config.candidates.map { it.exitId }
        val candidates = config.candidates.filter { it.exitId in needed }.toMutableList()
        automaticMain?.takeIf { it.first in needed }?.let { (exitId, tag) ->
            automatic?.candidates?.firstOrNull { it.tag == tag }?.let { candidate ->
                candidates.removeAll { it.exitId == exitId }
                candidates += ServiceRouteConfig.Candidate(exitId, tag, candidate.path, candidate.endpointHost.orEmpty())
            }
        }
        val observations = kotlinx.coroutines.coroutineScope {
            val limiter = Semaphore(4)
            candidates.filter { candidate -> config.policy.exits.any { it.exitId == candidate.exitId && it.state == "enabled" } }.map { candidate -> async {
                limiter.withPermit {
                    if (!valid(generation, guard)) return@withPermit null
                    val transport = try { effects.probe(candidate) } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
                    if (!valid(generation, guard)) return@withPermit null
                    val dns = transport && try { effects.dns(candidate) } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
                    Triple(candidate.exitId, transport, dns)
                }
            } }.awaitAll().filterNotNull()
        }
        if (!valid(generation, guard)) return false
        observations.forEach { (id, transport, dns) ->
            health.getOrPut(id, ::ServiceExitHealth).observe(transport)
            dnsHealth.getOrPut(id, ::ServiceExitHealth).observe(dns)
        }
        return updateTargets(indices, generation, guard)
    }

    private suspend fun updateTargets(indices: List<Int>, generation: Long, guard: () -> Boolean): Boolean {
        val main = automaticMain
        val alive = health.filterValues { it.alive }.keys
        val dnsAlive = alive.filter { dnsHealth[it]?.alive == true }.toMutableSet()
        if (main != null && !automaticHealthy) dnsAlive.remove(main.first)
        if (main != null) currentMainExitId = main.first
        else {
            val initialUsable = currentMainExitId == config.mainExitId && config.policy.exits.first { it.exitId == config.mainExitId }.state == "enabled" && health[config.mainExitId]?.wasConfirmedDown != true
            if (currentMainExitId !in alive && !initialUsable) {
                val country = config.policy.exits.first { it.exitId == config.mainExitId }.countryCode
                currentMainExitId = config.mainExitId.takeIf { it in alive } ?: if (config.allowMainFailover)
                    config.policy.exits.filter { it.exitId in alive }.sortedWith(compareBy({ it.countryCode != country }, { it.exitId })).firstOrNull()?.exitId ?: config.mainExitId
                    else config.mainExitId
            }
            val target = config.candidates.firstOrNull { it.exitId == currentMainExitId && (it.exitId in alive || initialUsable) }?.outboundTag ?: ServiceRouteConfig.BLOCK
            if (!setTarget(ServiceRouteConfig.MAIN, target, generation, guard)) return false
        }
        selection.mainExitId = currentMainExitId
        for (index in indices) {
            if (!valid(generation, guard)) return false
            val rule = config.policy.rules[index]
            val decision = selection.select(rule, dnsAlive)
            val tag = main?.takeIf { automaticHealthy && decision.target == it.first }?.second ?: config.tagFor(decision)
            if (!setTarget(ServiceRouteConfig.routeTag(index), tag, generation, guard)) return false
            states[rule.id] = ServiceRouteState(rule.id, decision.state, decision.exitId)
        }
        return true
    }

    private suspend fun setTarget(balancer: String, target: String, generation: Long, guard: () -> Boolean): Boolean {
        if (!valid(generation, guard)) return false
        if (currentTargets[balancer] == target && balancer !in unconfirmed) return true
        unconfirmed += balancer
        effects.write(balancer, target)
        if (!valid(generation, guard)) return false
        currentTargets[balancer] = target
        unconfirmed -= balancer
        return true
    }
    private fun valid(generation: Long, guard: () -> Boolean): Boolean = !closed.get() && !suspended.get() && epoch.get() == generation && guard()
    private fun checkValid(generation: Long, guard: () -> Boolean) { if (!valid(generation, guard)) throw CancellationException("Main policy reconciliation cancelled") }
    private fun snapshot(): List<ServiceRouteState> = config.policy.rules.map { states.getValue(it.id) }
    private suspend fun send(state: String, states: List<ServiceRouteState>, detail: String? = null, generation: Long = epoch.get()) {
        if (closed.get() || generation != epoch.get() || suspended.get()) return
        val next = ServiceRouteReport(config.policy.revision, config.policy.fingerprint, state, states, detail)
        val now = System.nanoTime()
        if (next == previousReport && now - lastReportAtNanos < 90_000_000_000L) return
        try { report(next); if (generation == epoch.get()) { previousReport = next; lastReportAtNanos = now } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Receipts never reset a working data plane. */ }
    }
    override fun close() {
        closed.set(true); epoch.incrementAndGet(); readyGate.complete(false)
        job?.cancel(); job = null; effects.close()
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
