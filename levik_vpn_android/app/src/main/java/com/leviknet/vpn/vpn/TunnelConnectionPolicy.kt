package com.leviknet.vpn.vpn

enum class TunnelNetworkRequirementViolation {
    CELLULAR_NETWORK_REQUIRED,
}

internal fun tunnelNetworkRequirementViolation(
    requirement: TunnelNetworkRequirement,
    isCellularNetwork: Boolean,
): TunnelNetworkRequirementViolation? = when (requirement) {
    TunnelNetworkRequirement.ANY -> null
    TunnelNetworkRequirement.CELLULAR_ALLOWLIST ->
        if (isCellularNetwork) null else TunnelNetworkRequirementViolation.CELLULAR_NETWORK_REQUIRED
}

class TunnelNetworkRequirementException(
    val violation: TunnelNetworkRequirementViolation,
) : Exception(
    when (violation) {
        TunnelNetworkRequirementViolation.CELLULAR_NETWORK_REQUIRED ->
            "A cellular network is required for this server"
    },
)
