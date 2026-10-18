package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.WhitelistMode

/**
 * When to probe the network for allowlists again, docs/app-platform.md
 * "Allowlists": every 30 seconds at first, then every 2 and 5 minutes while
 * the answer stays the same. Each probe opens several connections from the
 * radio, so a stable network is left alone. A changed answer, a new network
 * or a struggling tunnel starts over.
 */
internal class AllowlistProbeSchedule(
    private val intervalsMs: List<Long> = listOf(30_000L, 2 * 60_000L, 5 * 60_000L),
    private val roundsPerStep: Int = 2,
) {
    private var lastMode: WhitelistMode? = null
    private var stableRounds = 0
    private var step = 0

    init {
        require(intervalsMs.isNotEmpty())
        require(roundsPerStep > 0)
    }

    /**
     * The wait after a probe that answered [mode]. On a mobile server the way
     * back matters sooner: its traffic is limited, so the wait stops at the
     * second step.
     */
    @Synchronized
    fun next(mode: WhitelistMode, onMobileServer: Boolean): Long {
        if (mode == WhitelistMode.UNKNOWN || mode != lastMode) {
            step = 0
            stableRounds = 0
        } else if (++stableRounds >= roundsPerStep && step < intervalsMs.lastIndex) {
            step += 1
            stableRounds = 0
        }
        lastMode = mode
        val cap = if (onMobileServer) minOf(1, intervalsMs.lastIndex) else intervalsMs.lastIndex
        return intervalsMs[minOf(step, cap)]
    }

    @Synchronized
    fun reset() {
        lastMode = null
        stableRounds = 0
        step = 0
    }
}
