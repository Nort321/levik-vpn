package com.leviknet.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    PAUSED,
    STOPPING,
    ERROR,

    /** Kill Switch engaged: TUN captures all traffic and drops it until restored. */
    LOCKDOWN,
}

enum class VpnFailure {
    CORE_UNAVAILABLE,
    INVALID_PROFILE,
    PERMISSION_REVOKED,
    NETWORK,
    NETWORK_REQUIREMENT,
}

data class VpnSnapshot(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val engine: TunnelEngineKind? = null,
    val subscriptionId: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val downloadedBytes: Long = 0,
    val uploadedBytes: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val connectedDurationSeconds: Long = 0,
    val pausedRemainingSeconds: Long = 0,
    val failure: VpnFailure? = null,
    val failureDetail: String? = null,
    internal val ownerGeneration: Long = 0,
    internal val ownerActive: Boolean = false,
)

object VpnStateStore {
    private val mutableState = MutableStateFlow(VpnSnapshot())
    val state: StateFlow<VpnSnapshot> = mutableState.asStateFlow()

    internal fun claim(owner: Long) {
        mutableState.update { current ->
            if (owner > current.ownerGeneration) {
                VpnSnapshot(
                    ownerGeneration = owner,
                    ownerActive = true,
                )
            } else {
                current
            }
        }
    }

    internal fun update(owner: Long, transform: (VpnSnapshot) -> VpnSnapshot) {
        mutableState.update { current ->
            if (current.ownerGeneration == owner && current.ownerActive) {
                transform(current).copy(
                    ownerGeneration = owner,
                    ownerActive = true,
                )
            } else {
                current
            }
        }
    }

    internal fun set(owner: Long, snapshot: VpnSnapshot) {
        update(owner) { snapshot }
    }

    internal fun release(owner: Long, preserveTerminalError: Boolean) {
        mutableState.update { current ->
            if (current.ownerGeneration != owner || !current.ownerActive) {
                current
            } else if (preserveTerminalError && current.state == VpnConnectionState.ERROR) {
                current.copy(ownerActive = false)
            } else {
                VpnSnapshot(
                    ownerGeneration = owner,
                    ownerActive = false,
                )
            }
        }
    }
}
