package com.leviknet.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LteTrafficAccumulatorTest {
    @Test
    fun addsLiveSessionTrafficToServerBaseline() {
        val accumulator = LteTrafficAccumulator()

        assertEquals(500L, accumulator.estimateUsedBytes("sub", "lte", 500L, 0L))
        assertEquals(620L, accumulator.estimateUsedBytes("sub", "lte", 500L, 120L))
        assertEquals(700L, accumulator.estimateUsedBytes("sub", "lte", 700L, 120L))
    }

    @Test
    fun preservesLocallyObservedTrafficWhenSessionCounterRestarts() {
        val accumulator = LteTrafficAccumulator()

        accumulator.estimateUsedBytes("sub", "lte", 1_000L, 200L)
        assertEquals(
            1_250L,
            accumulator.estimateUsedBytes("sub", "lte", 1_000L, 50L),
        )
    }

    @Test
    fun resetsBaselineForAnotherSubscriptionOrServer() {
        val accumulator = LteTrafficAccumulator()

        accumulator.estimateUsedBytes("sub-a", "lte-a", 1_000L, 200L)
        assertEquals(
            75L,
            accumulator.estimateUsedBytes("sub-b", "lte-b", 50L, 25L),
        )
    }
}
