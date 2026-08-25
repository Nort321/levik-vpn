package com.leviknet.vpn.ui

internal class LteTrafficAccumulator {
    private var subscriptionId: String? = null
    private var serverId: String? = null
    private var serverBaselineBytes = 0L
    private var locallyObservedBytes = 0L
    private var lastSessionBytes = 0L

    fun estimateUsedBytes(
        subscriptionId: String,
        serverId: String,
        serverUsedBytes: Long,
        sessionBytes: Long,
    ): Long {
        val safeServerUsed = serverUsedBytes.coerceAtLeast(0L)
        val safeSession = sessionBytes.coerceAtLeast(0L)
        if (this.subscriptionId != subscriptionId || this.serverId != serverId) {
            this.subscriptionId = subscriptionId
            this.serverId = serverId
            serverBaselineBytes = safeServerUsed
            locallyObservedBytes = safeSession
            lastSessionBytes = safeSession
        } else {
            val delta = if (safeSession >= lastSessionBytes) {
                safeSession - lastSessionBytes
            } else {
                safeSession
            }
            locallyObservedBytes = saturatingAdd(locallyObservedBytes, delta)
            lastSessionBytes = safeSession
        }
        return maxOf(
            safeServerUsed,
            saturatingAdd(serverBaselineBytes, locallyObservedBytes),
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
