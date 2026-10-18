package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.WhitelistMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AllowlistProbeScheduleTest {
    private val minute = 60_000L

    @Test
    fun `a stable network is probed less and less often`() {
        val schedule = AllowlistProbeSchedule()
        val waits = List(7) { schedule.next(WhitelistMode.INACTIVE, onMobileServer = false) }
        assertEquals(listOf(30_000L, 30_000L, 2 * minute, 2 * minute, 5 * minute, 5 * minute, 5 * minute), waits)
    }

    @Test
    fun `a changed answer, an unknown one or a reset starts over`() {
        val schedule = AllowlistProbeSchedule()
        repeat(5) { schedule.next(WhitelistMode.INACTIVE, onMobileServer = false) }
        assertEquals(30_000L, schedule.next(WhitelistMode.ACTIVE, onMobileServer = false))
        repeat(4) { schedule.next(WhitelistMode.ACTIVE, onMobileServer = false) }
        assertEquals(30_000L, schedule.next(WhitelistMode.UNKNOWN, onMobileServer = false))
        repeat(4) { schedule.next(WhitelistMode.ACTIVE, onMobileServer = false) }
        schedule.reset()
        assertEquals(30_000L, schedule.next(WhitelistMode.ACTIVE, onMobileServer = false))
    }

    @Test
    fun `on a mobile server the wait stops at two minutes`() {
        val schedule = AllowlistProbeSchedule()
        val waits = List(6) { schedule.next(WhitelistMode.ACTIVE, onMobileServer = true) }
        assertEquals(2 * minute, waits.last())
    }
}
