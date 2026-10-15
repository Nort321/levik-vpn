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

/**
 * Adds the apps the account's rules send around the VPN to this device's own
 * choice. A device that tunnels only chosen apps keeps its list: every other
 * app already goes around the VPN.
 */
internal fun withAccountDirectApps(
    mode: SplitTunnelMode,
    configuredPackages: Set<String>,
    directApps: Collection<String>,
): Pair<SplitTunnelMode, Set<String>> = when {
    directApps.isEmpty() -> mode to configuredPackages
    mode == SplitTunnelMode.OFF -> SplitTunnelMode.DISALLOWED to directApps.toSet()
    mode == SplitTunnelMode.DISALLOWED -> mode to configuredPackages + directApps
    else -> mode to configuredPackages
}

/** Apply UID-level routing independently of the server, transport, and destination rules. */
internal fun applySplitTunnelApplications(
    mode: SplitTunnelMode,
    configuredPackages: Set<String>,
    vpnPackageName: String,
    addAllowed: (String) -> Unit,
    addDisallowed: (String) -> Unit,
) {
    val packages = splitTunnelPackagesForBuilder(mode, configuredPackages, vpnPackageName)
    when (mode) {
        SplitTunnelMode.ALLOWED -> packages.forEach(addAllowed)
        SplitTunnelMode.DISALLOWED -> packages.forEach(addDisallowed)
        SplitTunnelMode.OFF -> Unit
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
