package com.leviknet.vpn.vpn

import android.net.Network
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Runs only before the service establishes a TUN. An active core is never replaced by a probe. */
internal class SmartRoutePreflight(
    private val runtime: XrayRuntime,
    private val engines: TunnelEngineRegistry,
    private val json: Json,
    private val privateDirectory: File,
) {
    suspend fun select(
        owner: Long,
        profile: PreparedTunnelProfile,
        servers: List<TunnelServer>,
        network: Network,
        smartAuto: SmartAuto,
        fallbackServerId: String?,
        environment: (TunnelServer) -> TunnelEngineEnvironment,
        baseConfig: (PreparedTunnelProfile, String) -> String,
    ): String? = withContext(Dispatchers.IO) {
        val candidates = SmartRouteSelection.candidates(smartAuto.eligibleRoutes(servers))
        val contexts = candidates.associate { it.id to smartAuto.measurementContext(it) }
        val routeIds = smartAuto.routeResources(candidates).mapValues { it.value.id }
        val previous = smartAuto.routeMeasurements(candidates, network)
        if (previous.keys.containsAll(candidates.map { it.id })) {
            SmartRouteSelection.order(previous, smartAuto.routeResources(servers), smartAuto.eligibleRoutes(servers).map(TunnelServer::id))
                .firstOrNull()?.let { return@withContext it }
        }
        val native = mutableListOf<NativeRouteProbe>()
        val measurements = java.util.concurrent.ConcurrentHashMap<String, SmartRouteMeasurement>()
        val byId = candidates.associateBy(TunnelServer::id)
        fun measured(id: String, value: SmartRouteMeasurement) {
            measurements[id] = value
            byId[id]?.let { smartAuto.recordRouteMeasurement(it, network, value, contexts.getValue(id), routeIds[id]) }
        }
        try {
            withTimeoutOrNull(22_000L) {
                val regular = candidates.filter { it.engine == TunnelEngineKind.XRAY }
                val tuic = mutableListOf<TunnelServer>()
                // Native TUIC owns its physical sockets; Xray must not bind its local SOCKS sockets.
                withTimeoutOrNull(4_000L) {
                    for (server in candidates.filter { it.engine == TunnelEngineKind.LEVIK_TUIC }.take(4)) {
                        ensureActive()
                        val prepared = try {
                            engines.require(server.engine).prepareRouteProbe(server, environment(server))
                        } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                        if (prepared != null) {
                            native += prepared
                            tuic += XrayConfigBuilder(json).withTuicProxy(profile, server.id, prepared.proxy).servers.first()
                        }
                    }
                }
                val batches = listOf(regular to false, tuic to true).filter { it.first.isNotEmpty() }
                suspend fun probeBatch(batch: Pair<List<TunnelServer>, Boolean>, speed: Map<String, SmartRouteMeasurement>? = null) {
                    val (batchServers, localOnly) = batch
                    val config = SmartRouteProbeConfig(batchServers, privateDirectory)
                    var lease: Long? = null
                    try {
                        val probeProfile = profile.copy(servers = batchServers, serviceRoutes = null)
                        val base = json.parseToJsonElement(baseConfig(probeProfile, batchServers.first().id)).jsonObject
                        val env = environment(byId.getValue(batchServers.first().id))
                        lease = runtime.start(owner, json.encodeToString(JsonObject.serializer(), config.apply(base)),
                            object : libXray.DialerController {
                                override fun protectFd(fd: Long): Boolean = if (localOnly) env.unboundSocketProtector(fd) else env.protector.protectAndBind(fd)
                            }, if (localOnly) null else env.dnsServer, replaceActive = false)
                        SmartRouteProber(config.candidates).use { prober ->
                            if (speed == null) prober.measureLatency(onMeasurement = ::measured) else prober.measureBandwidth(speed, ::measured)
                        }
                    } catch (error: CancellationException) { throw error } catch (_: Exception) {
                        // Probe infrastructure being unavailable does not disable ordinary VPN connections.
                    } finally {
                        lease?.let { runtime.stop(owner, it) }
                        config.close()
                    }
                }
                for (batch in batches) probeBatch(batch)
                val passing = measurements.filterValues { it.samplesMs.isNotEmpty() }
                val resources = smartAuto.routeResources(candidates)
                val healthy = passing.filterKeys { resources[it]?.overloaded != true }.ifEmpty { passing }
                val ordered = healthy.keys.sortedBy { healthy.getValue(it).delayCost }
                val finalists = linkedSetOf<String>()
                ordered.firstOrNull()?.let(finalists::add)
                healthy.maxByOrNull { it.value.screeningMbps ?: 0.0 }?.key?.let(finalists::add)
                val firstEntry = ordered.firstOrNull()?.let(byId::get)?.syncHost()
                ordered.firstOrNull { byId[it]?.syncHost() != firstEntry && it !in finalists }?.let(finalists::add)
                ordered.firstOrNull { it !in finalists }?.takeIf { finalists.size < 3 }?.let(finalists::add)
                // Across all engines there are at most three bounded down/up tests, run sequentially.
                for (batch in batches) {
                    val ids = batch.first.map(TunnelServer::id).toSet()
                    val selected = finalists.filter { it in ids }.associateWith { measurements.getValue(it) }
                    if (selected.isNotEmpty()) probeBatch(batch, selected)
                }
            }
        } finally { native.forEach { runCatching { it.close() } } }
        val currentRoutes = smartAuto.routeResources(candidates)
        val current = measurements.filterKeys { id -> byId[id]?.let { smartAuto.measurementContext(it) == contexts[id] } == true && routeIds[id] == currentRoutes[id]?.id }
        val eligible = smartAuto.eligibleRoutes(servers)
        SmartRouteSelection.select(current, smartAuto.routeResources(eligible), eligible.map(TunnelServer::id), fallbackServerId)
    }
}
