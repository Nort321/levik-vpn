package com.leviknet.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** One status line of the Game sidecar (native/game/source/game/status.go); no addresses. */
@Serializable
data class GameRuntimeStatus(
    /** protected (two copies), single, unavailable. */
    val state: String = "unavailable",
    val exitNodeId: String? = null,
    val exitName: String? = null,
    val latencyMs: Double? = null,
    val playing: Boolean = false,
    val flows: Int = 0,
    /** The exit's answer to the relay token: ok, invalid, busy, limit. */
    val auth: String? = null,
    val receivedPackets: Long = 0,
    val duplicateReplies: Long = 0,
    val uploadedPackets: Long = 0,
    val droppedPackets: Long = 0,
)

object GameStatusStore {
    private val mutableStatus = MutableStateFlow<GameRuntimeStatus?>(null)
    val status: StateFlow<GameRuntimeStatus?> = mutableStatus.asStateFlow()

    fun update(status: GameRuntimeStatus?) {
        mutableStatus.value = status
    }
}
