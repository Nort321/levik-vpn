package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLadderTest {
    @Test
    fun `a few climbs spend the ladder`() {
        var now = 0L
        val ladder = SmartLadder(maxClimbs = 2, retryAfterMs = 60_000, clock = { now })
        ladder.tried("a", "vless-reality")
        ladder.climbed()
        assertFalse(ladder.exhausted())
        ladder.tried("b", "hysteria2")
        ladder.climbed()
        now += 59_999
        assertTrue(ladder.exhausted())
        assertEquals(setOf("a", "b"), ladder.ids())
    }

    @Test
    fun `a spent ladder is climbed again after a while`() {
        var now = 0L
        val ladder = SmartLadder(maxClimbs = 1, retryAfterMs = 60_000, clock = { now })
        ladder.tried("a", "vless-reality")
        ladder.climbed()
        assertTrue(ladder.exhausted())
        now += 60_000
        assertFalse(ladder.exhausted())
        assertTrue(ladder.ids().isEmpty())
        assertTrue(ladder.protocols().isEmpty())
        // The fresh climb is bounded like the first.
        ladder.climbed()
        assertTrue(ladder.exhausted())
    }
}
