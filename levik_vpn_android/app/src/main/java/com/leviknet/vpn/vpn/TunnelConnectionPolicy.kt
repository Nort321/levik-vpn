package com.leviknet.vpn.vpn

import com.leviknet.vpn.data.SplitTunnelMode

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

/** Keeps service-owned health probes inside the VPN regardless of user split-tunnel choices. */
internal fun splitTunnelPackagesForBuilder(
    mode: SplitTunnelMode,
    configuredPackages: Set<String>,
    vpnPackageName: String,
): Set<String> = when (mode) {
    SplitTunnelMode.OFF -> emptySet()
    SplitTunnelMode.DISALLOWED -> configuredPackages - vpnPackageName
    SplitTunnelMode.ALLOWED -> {
        if (configuredPackages.isEmpty()) emptySet() else configuredPackages + vpnPackageName
    }
}

class TunnelNetworkRequirementException(
    val violation: TunnelNetworkRequirementViolation,
) : Exception(
    when (violation) {
        TunnelNetworkRequirementViolation.CELLULAR_NETWORK_REQUIRED ->
            "A cellular network is required for this server"
    },
)
