package com.leviknet.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPingCacheTest {
    private val fingerprints = mapOf("a" to "config-a", "b" to "config-b")

    @Test
    fun `country and connect reuse a full catalog measurement including an unknown UDP ping`() {
        val cache = ServerPingCache()
        cache.store("wifi-1", fingerprints, mapOf("a" to 25L, "b" to null), 100L)
        assertEquals(mapOf("a" to 25L), cache.read("wifi-1", mapOf("a" to "config-a"), 500L))
        assertEquals(mapOf("a" to 25L, "b" to null), cache.read("wifi-1", fingerprints, 500L))
    }

    @Test
    fun `different physical network and changed credentials cannot reuse old probes`() {
        val cache = ServerPingCache()
        cache.store("wifi-1", fingerprints, mapOf("a" to 25L), 100L)
        assertTrue(cache.read("lte-1", fingerprints, 500L).isEmpty())
        assertTrue(cache.read(null, fingerprints, 500L).isEmpty())
        assertTrue(cache.read("wifi-1", mapOf("a" to "new-credentials"), 500L).isEmpty())
        cache.store("lte-1", fingerprints, mapOf("b" to 70L), 600L)
        assertTrue(cache.read("wifi-1", fingerprints, 700L).isEmpty())
        assertEquals(mapOf("b" to 70L), cache.read("lte-1", fingerprints, 700L))
    }

    @Test
    fun `failures expire early and neither expired nor future measurements rank servers`() {
        val cache = ServerPingCache()
        cache.store("wifi-1", fingerprints, mapOf("a" to 25L, "b" to null), 100L)
        assertEquals(mapOf("a" to 25L), cache.read("wifi-1", fingerprints, 100L + ServerPingCache.FAILURE_TTL_MS))
        assertTrue(cache.read("wifi-1", fingerprints, 100L + ServerPingCache.SUCCESS_TTL_MS).isEmpty())
        assertTrue(cache.read("wifi-1", fingerprints, 99L).isEmpty())
    }
}
