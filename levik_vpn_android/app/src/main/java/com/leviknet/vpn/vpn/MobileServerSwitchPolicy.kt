package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.WhitelistMode

internal enum class MobileServerSwitchDecision {
    NONE,
    TO_MOBILE,
    TO_REGULAR,
}

/** Debounces contradictory carrier probes and prevents reconnect loops. */
internal class MobileServerSwitchPolicy(
    private val requiredConfirmations: Int = 2,
    private val cooldownMs: Long = 90_000L,
) {
    private var candidate = MobileServerSwitchDecision.NONE
    private var candidateNetwork: String? = null
    private var confirmations = 0
    private var lastDecisionAtMs = Long.MIN_VALUE

    init {
        require(requiredConfirmations > 0)
        require(cooldownMs >= 0L)
    }

    fun evaluate(
        automaticServer: Boolean,
        currentServerIsMobile: Boolean,
        physicalNetworkIsCellular: Boolean,
        whitelistMode: WhitelistMode,
        networkIdentity: String,
        nowMs: Long,
    ): MobileServerSwitchDecision {
        val next = when {
            !automaticServer || whitelistMode == WhitelistMode.UNKNOWN ->
                MobileServerSwitchDecision.NONE
            !currentServerIsMobile && physicalNetworkIsCellular &&
                whitelistMode == WhitelistMode.ACTIVE -> MobileServerSwitchDecision.TO_MOBILE
            currentServerIsMobile && whitelistMode == WhitelistMode.INACTIVE ->
                MobileServerSwitchDecision.TO_REGULAR
            else -> MobileServerSwitchDecision.NONE
        }
        if (next == MobileServerSwitchDecision.NONE) {
            resetCandidate()
            return next
        }
        if (next != candidate || networkIdentity != candidateNetwork) {
            candidate = next
            candidateNetwork = networkIdentity
            confirmations = 1
        } else {
            confirmations += 1
        }
        val cooldownElapsed = lastDecisionAtMs == Long.MIN_VALUE ||
            nowMs - lastDecisionAtMs >= cooldownMs
        if (confirmations < requiredConfirmations || !cooldownElapsed) {
            return MobileServerSwitchDecision.NONE
        }
        lastDecisionAtMs = nowMs
        resetCandidate()
        return next
    }

    fun reset() {
        lastDecisionAtMs = Long.MIN_VALUE
        resetCandidate()
    }

    private fun resetCandidate() {
        candidate = MobileServerSwitchDecision.NONE
        candidateNetwork = null
        confirmations = 0
    }
}
