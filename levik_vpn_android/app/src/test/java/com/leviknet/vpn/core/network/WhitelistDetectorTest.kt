package com.leviknet.vpn.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WhitelistDetectorTest {
    @Test
    fun `detects allow-list mode only when domestic sites work and external sites do not`() {
        val results = probes(
            domestic = listOf(true, true, false),
            external = listOf(false, false, false),
        )

        assertEquals(WhitelistMode.ACTIVE, classifyWhitelistMode(results))
    }

    @Test
    fun `normal internet is not classified as allow-list mode`() {
        val results = probes(
            domestic = listOf(true, true, true),
            external = listOf(true, true, true),
        )

        assertEquals(WhitelistMode.INACTIVE, classifyWhitelistMode(results))
    }

    @Test
    fun `complete outage remains unknown instead of producing a false warning`() {
        val results = probes(
            domestic = listOf(false, false, false),
            external = listOf(false, false, false),
        )

        assertEquals(WhitelistMode.UNKNOWN, classifyWhitelistMode(results))
    }

    private fun probes(
        domestic: List<Boolean>,
        external: List<Boolean>,
    ): List<WhitelistProbeResult> =
        domestic.mapIndexed { index, reachable ->
            WhitelistProbeResult("domestic-$index", domestic = true, reachable = reachable)
        } + external.mapIndexed { index, reachable ->
            WhitelistProbeResult("external-$index", domestic = false, reachable = reachable)
        }
}
