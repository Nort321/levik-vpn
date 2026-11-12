package com.leviknet.vpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ExternalServerPoolTest {
    private fun server(index: Int = 1, address: String = "8.8.8.8"): TunnelServer {
        val fingerprint = index.toString(16).padStart(64, '0')
        val tag = "external-node-$fingerprint"
        return TunnelServer("external:$fingerprint", tag, "Imported", "XX", Json.parseToJsonElement("""
            {"tag":"$tag","protocol":"trojan","settings":{"servers":[{"address":"$address","port":443,"password":"fixture"}]}}
        """).let { it as JsonObject })
    }
    private fun pool(servers: List<TunnelServer>) = ExternalServerPool(1, "2", "2026-11-12T00:00:00Z", servers)

    @Test fun `the full 326-node catalog survives saved profile serialization outside the visible list`() {
        val catalog = pool((0 until 326).map { server(it) }).validated()
        val prepared = PreparedTunnelProfile(1, "p", "s", "2026-11-12T00:00:00Z", servers = listOf(externalAutoServer()), externalPool = catalog)
        val restored = Json.decodeFromString<PreparedTunnelProfile>(Json.encodeToString(PreparedTunnelProfile.serializer(), prepared))
        assertEquals(326, restored.externalPool?.servers?.size)
        assertEquals(listOf(EXTERNAL_AUTO_ID), restored.servers.map { it.id })
        assertFalse(restored.servers.first().isEligibleForAutomaticSelection())
        assertFalse(catalog.servers.first().isSmartAutoCandidate())
    }
    @Test fun `untrusted pool names and nonpublic numeric endpoints cannot dial the local network`() {
        for (address in listOf("localhost", "evil.example", "127.0.0.1", "10.0.0.1", "100.64.0.1", "192.168.1.1", "::1", "::ffff:127.0.0.1")) {
            assertFalse(address, publicExternalAddress(address))
            assertTrue(pool(listOf(server(address = address))).validated().servers.isEmpty())
        }
        assertTrue(publicExternalAddress("8.8.8.8"))
        assertTrue(publicExternalAddress("2606:4700:4700::1111"))
        assertTrue(publicExternalAddress("2001:4860:4860::8888"))
        assertFalse(publicExternalAddress("2::1"))
    }
    @Test fun `duplicates and injected dialers do not enter the core`() {
        val source = server()
        val unsafe = source.copy(outbound = Json.parseToJsonElement(source.outbound.toString().dropLast(1) +
            ",\"streamSettings\":{\"sockopt\":{\"dialerProxy\":\"direct\"}}}") as JsonObject)
        assertEquals(1, pool(listOf(source, source)).validated().servers.size)
        assertTrue(pool(listOf(unsafe)).validated().servers.isEmpty())
    }
    @Test fun `a refresh without a pool preserves the saved list for the same subscription only`() {
        val previous = PreparedTunnelProfile(1, "p", "one", "2026-11-12T00:00:00Z", servers = listOf(externalAutoServer()), externalPool = pool(listOf(server())))
        val refreshed = previous.copy(profileId = "new", servers = listOf(server(2)), externalPool = null)
        val retained = retainExternalPool(refreshed, previous)
        assertEquals(previous.externalPool, retained.externalPool)
        assertTrue(retained.servers.any { it.id == EXTERNAL_AUTO_ID })
        assertNull(retainExternalPool(refreshed.copy(subscriptionId = "two"), previous).externalPool)
        assertNull(retainExternalPool(refreshed.copy(gameOnly = true), previous).externalPool)
    }
    @Test fun `external selection is available independently of Game engine support`() {
        val raw = TunnelProfile(1, profileId = "p", subscriptionId = "one", issuedAt = "2026-11-12T00:00:00Z",
            externalPool = pool((0 until 326).map { server(it) }))
        val prepared = TunnelProfilePreparer(XrayRuntime(Json), includeExternalPool = true).prepare(raw)
        assertEquals(listOf(EXTERNAL_AUTO_ID), prepared.servers.map { it.id })
        assertEquals(326, prepared.externalPool?.servers?.size)
    }
}
