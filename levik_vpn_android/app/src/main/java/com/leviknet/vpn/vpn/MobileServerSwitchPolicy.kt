package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.network.WhitelistMode

internal enum class MobileServerSwitchDecision {
    NONE,
    TO_MOBILE,
    TO_REGULAR,
}

/** Android validation is enough to leave a mobile carrier; no allowlist probe is needed on Wi-Fi. */
internal fun shouldReturnFromMobileServer(
    currentServerIsMobile: Boolean,
    preferredNetworkIsCellular: Boolean,
    preferredNetworkIsValidated: Boolean,
    automaticServer: Boolean,
    returnPending: Boolean,
): Boolean = currentServerIsMobile && !preferredNetworkIsCellular && preferredNetworkIsValidated &&
    (automaticServer || returnPending)

/**
 * Debounces contradictory carrier probes and prevents reconnect loops.
 *
 * Allowlists switch to a mobile server whether the server was chosen
 * automatically or by hand: a regular server carries nothing then. The way
 * back is automatic in Auto mode; with a server chosen by hand only to the
 * server the app itself moved away from ([returnPending]).
 */
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
        returnPending: Boolean = false,
        userOverride: Boolean = false,
    ): MobileServerSwitchDecision {
        val next = when {
            whitelistMode == WhitelistMode.UNKNOWN -> MobileServerSwitchDecision.NONE
            // The user picked a regular server under allowlists on this network: theirs to keep.
            !currentServerIsMobile && physicalNetworkIsCellular && !userOverride &&
                whitelistMode == WhitelistMode.ACTIVE -> MobileServerSwitchDecision.TO_MOBILE
            currentServerIsMobile && (automaticServer || returnPending) &&
                whitelistMode == WhitelistMode.INACTIVE -> MobileServerSwitchDecision.TO_REGULAR
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

    /** A switch waits for its confirmation: the next probe should come soon. */
    fun hasPendingCandidate(): Boolean = candidate != MobileServerSwitchDecision.NONE

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
