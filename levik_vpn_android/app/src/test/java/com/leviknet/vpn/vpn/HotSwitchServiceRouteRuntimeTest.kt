package com.leviknet.vpn.vpn

import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HotSwitchServiceRouteRuntimeTest {
    private class Harness(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO) : AutoCloseable {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "hs-runtime-").toFile()
        fun server(id: String, country: String, last: Int) = TunnelServer(id, id, id, country,
            Json.parseToJsonElement("""{"tag":"$id","protocol":"vless","settings":{"address":"192.0.2.$last","port":443}}""").jsonObject)
        val servers = listOf(server("a", "DE", 1), server("b", "NL", 2), server("fixed", "FI", 3))
        val policy = ServiceRouteProfile(1, 1, "a".repeat(64), listOf(
            ServiceRouteRule("main", listOf("full:main.example"), ServiceRouteTarget("main"), ServiceRouteTarget("block"), "domain", "Main"),
            ServiceRouteRule("fallback", listOf("full:fallback.example"), ServiceRouteTarget("country", countryCode = "US"), ServiceRouteTarget("main"), "domain", "Fallback"),
            ServiceRouteRule("fixed", listOf("full:fixed.example"), ServiceRouteTarget("exit", exitId = "fixed"), ServiceRouteTarget("block"), "domain", "Fixed"),
        ), servers.map { ServiceRouteExit(it.id, it.name, it.countryCode, "enabled", "ordinary", listOf(ServiceRouteEndpoint(requireNotNull(it.syncHost()), 443, "vless"))) })
        val profile = PreparedTunnelProfile(1, "test", "subscription", "2026-10-30T00:00:00Z", servers = servers, serviceRoutes = policy)
        val config = requireNotNull(ServiceRouteConfig.prepare(profile, servers.first(), folder, true))
        val live = SmartAutoLiveConfig(servers, "a", folder, config)
        val actual = mutableMapOf<String, String>()
        val writes = mutableListOf<Pair<String, String>>()
        val probes = mutableListOf<String>()
        val reports = mutableListOf<ServiceRouteReport>()
        var write: suspend (String, String) -> Unit = { balancer, target -> actual[balancer] = target }
        var probe: suspend (ServiceRouteConfig.Candidate) -> Boolean = { true }
        var dns: suspend (ServiceRouteConfig.Candidate) -> Boolean = { true }
        val runtime = ServiceRouteRuntime(config, scope, live, ServiceRouteRuntimeEffects(
            write = { balancer, target -> writes += balancer to target; write(balancer, target) },
            probe = { candidate -> probes += candidate.exitId; probe(candidate) }, dns = { dns(it) },
        ), dispatcher) { reports += it }
        fun tag(id: String) = live.candidates.first { it.id == id }.tag
        suspend fun commit(id: String, guard: () -> Boolean = { true }) = runtime.commitExternalMain(id, tag(id), guard)
        override fun close() { runtime.close(); live.close(); config.close(); folder.delete() }
    }

    @Test fun `manual reconciliation follows MAIN and fallback MAIN without probing a fixed country`() = runTest {
        Harness(this).use { h ->
            h.runtime.refresh(); h.writes.clear(); h.probes.clear()
            h.commit("b")
            assertEquals(listOf(ServiceRouteConfig.routeTag(0) to h.tag("b"), ServiceRouteConfig.routeTag(1) to h.tag("b")), h.writes)
            assertEquals(listOf("b"), h.probes)
            assertEquals("fixed", h.actual[ServiceRouteConfig.routeTag(2)])
            assertEquals(listOf("b", "b", "fixed"), h.reports.last().routes.map { it.exitId })
            assertFalse(h.writes.any { it.first == ServiceRouteConfig.MAIN })
        }
    }

    @Test fun `stale submitted policy write is reasserted even when restoring its cached old target`() = runTest {
        Harness(this).use { h ->
            h.runtime.refresh(); h.writes.clear(); h.reports.clear()
            val gate = CompletableDeferred<Unit>()
            h.write = { balancer, target -> h.actual[balancer] = target; if (target == h.tag("b")) gate.await() }
            val old = async { runCatching { h.commit("b") } }; runCurrent()
            assertEquals(h.tag("b"), h.actual[ServiceRouteConfig.routeTag(0)])
            val restore = async { h.commit("a") }; runCurrent()
            gate.complete(Unit)
            assertTrue(old.await().isFailure); restore.await()
            assertEquals(listOf(ServiceRouteConfig.routeTag(0) to h.tag("b"), ServiceRouteConfig.routeTag(0) to h.tag("a")), h.writes)
            assertEquals(h.tag("a"), h.actual[ServiceRouteConfig.routeTag(1)])
            assertTrue(h.reports.isEmpty()) // The unchanged acknowledged A receipt stays valid.
        }
    }

    @Test fun `acknowledged compensation during suspension is remembered before policy resumes`() = runTest {
        Harness(this).use { h ->
            h.runtime.refresh(); h.writes.clear(); h.reports.clear()
            val gate = CompletableDeferred<Boolean>()
            h.probe = { if (it.exitId == "b") gate.await() else true }
            val old = async { runCatching { h.commit("b") } }; runCurrent()
            h.runtime.setSuspended(true)
            val restore = async { runCatching { h.commit("a") } }; runCurrent()
            gate.complete(true)
            assertTrue(old.await().isFailure); assertTrue(restore.await().isFailure)
            assertTrue(h.writes.isEmpty()); assertTrue(h.reports.isEmpty())
            h.runtime.setSuspended(false); h.runtime.refresh()
            assertEquals(h.tag("a"), h.actual[ServiceRouteConfig.routeTag(0)])
            assertEquals(h.tag("a"), h.actual[ServiceRouteConfig.routeTag(1)])
            assertTrue(h.reports.isEmpty()) // Resume does not manufacture a changed receipt.
        }
    }

    @Test fun `guard invalidation after an RPC stops the remaining writes and compensation repairs the dirty rule`() = runTest {
        Harness(this).use { h ->
            h.runtime.refresh(); h.writes.clear(); h.reports.clear()
            var allowed = true
            h.write = { balancer, target -> h.actual[balancer] = target; if (target == h.tag("b")) allowed = false }
            assertTrue(runCatching { h.commit("b") { allowed } }.isFailure)
            assertEquals(listOf(ServiceRouteConfig.routeTag(0) to h.tag("b")), h.writes)
            assertTrue(h.reports.isEmpty())
            h.commit("a")
            assertEquals(h.tag("a"), h.actual[ServiceRouteConfig.routeTag(0)])
            assertEquals(h.tag("a"), h.actual[ServiceRouteConfig.routeTag(1)])
        }
    }

    @Test fun `a lost policy reply rejects and leaves a dirty write for acknowledged compensation`() = runTest {
        Harness(this).use { h ->
            h.runtime.refresh(); h.writes.clear()
            h.write = { balancer, target -> h.actual[balancer] = target; if (target == h.tag("b")) error("Lost acknowledgement") }
            assertTrue(runCatching { h.commit("b") }.isFailure)
            h.commit("a")
            assertEquals(h.tag("a"), h.actual[ServiceRouteConfig.routeTag(0)])
            assertEquals(listOf(ServiceRouteConfig.routeTag(0) to h.tag("b"), ServiceRouteConfig.routeTag(0) to h.tag("a")), h.writes)
        }
    }

    @Test fun `DNS failure blocks dependent rules without moving the fixed rule or external MAIN`() = runTest {
        Harness(this).use { h ->
            h.runtime.refresh(); h.writes.clear()
            h.dns = { it.exitId != "b" }
            h.commit("b")
            assertEquals(listOf(ServiceRouteConfig.routeTag(0) to ServiceRouteConfig.BLOCK, ServiceRouteConfig.routeTag(1) to ServiceRouteConfig.BLOCK), h.writes)
            assertEquals("fixed", h.actual[ServiceRouteConfig.routeTag(2)])
            assertEquals(listOf("blocked", "blocked", "active"), h.reports.last().routes.map { it.state })
        }
    }

    @Test fun `closed or unknown sessions cannot apply a late external target`() = runTest {
        Harness(this).use { h ->
            assertFalse(h.runtime.supportsExternalMain("unknown", "unknown"))
            assertTrue(runCatching { h.runtime.commitExternalMain("unknown", "unknown") }.isFailure)
            h.runtime.close()
            assertTrue(runCatching { h.commit("b") }.isFailure)
            assertTrue(h.writes.isEmpty()); assertTrue(h.probes.isEmpty())
        }
    }

    @Test fun `a superseded child probe cannot kill the periodic health loop`() = runTest {
        Harness(backgroundScope, StandardTestDispatcher(testScheduler)).use { h ->
            val gate = CompletableDeferred<Unit>()
            var first = true
            h.probe = { candidate ->
                if (candidate.exitId == "a" && first) {
                    first = false; gate.await()
                    assertTrue(currentCoroutineContext().isActive)
                    throw CancellationException("Old probe superseded")
                }
                true
            }
            h.runtime.start(); runCurrent()
            assertTrue("a" in h.probes)
            val switching = async { h.commit("b") }; runCurrent()
            gate.complete(Unit); runCurrent(); switching.await()
            val before = h.probes.size
            advanceTimeBy(10_001); runCurrent()
            assertTrue("The next periodic health round did not run", h.probes.size > before)
            assertEquals("fixed", h.reports.last().routes.last().exitId)
            h.runtime.close()
            val stopped = h.probes.size
            advanceTimeBy(10_001); runCurrent()
            assertEquals(stopped, h.probes.size)
        }
    }

    @Test fun `direct and default routes never inherit an ignored fallback MAIN or require DNS probes`() = runTest {
        Harness(this).use { h ->
            val rules = listOf(
                ServiceRouteRule("direct", listOf("full:direct.example"), ServiceRouteTarget("direct"), ServiceRouteTarget("main"), "domain", "Direct"),
                ServiceRouteRule("default", listOf("full:default.example"), ServiceRouteTarget("default"), ServiceRouteTarget("main"), "domain", "Default"),
            )
            val policy = h.policy.copy(rules = rules)
            val config = requireNotNull(ServiceRouteConfig.prepare(h.profile.copy(serviceRoutes = policy), h.servers.first(), h.folder, true))
            val live = SmartAutoLiveConfig(h.servers, "a", h.folder, config)
            val writes = mutableListOf<Pair<String, String>>()
            val runtime = ServiceRouteRuntime(config, this, live, ServiceRouteRuntimeEffects(
                write = { balancer, target -> writes += balancer to target },
                probe = { error("Unnecessary direct/default transport probe") },
                dns = { error("Unnecessary direct/default DNS probe") },
            )) {}
            try {
                runtime.refresh()
                assertEquals(listOf(ServiceRouteConfig.routeTag(0) to ServiceRouteConfig.DIRECT,
                    ServiceRouteConfig.routeTag(1) to "levik-sr-base-out"), writes)
                runtime.commitExternalMain("b", live.candidates.first { it.id == "b" }.tag)
                assertEquals(2, writes.size)
            } finally { runtime.close(); live.close(); config.close() }
        }
    }
}
