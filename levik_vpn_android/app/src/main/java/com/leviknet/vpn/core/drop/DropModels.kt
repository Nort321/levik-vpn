package com.leviknet.vpn.core.drop

/** LevikDrop as the screen sees it, docs/levik-drop.md. Files, keys and sockets stay in [DropEngine]. */

enum class DropState(val wire: String) {
    OFFERED("offered"),
    ACTIVE("active"),
    PAUSED("paused"),
    DONE("done"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    EXPIRED("expired"),
    ;

    val open: Boolean get() = this == OFFERED || this == ACTIVE || this == PAUSED

    companion object {
        fun of(value: String?): DropState? = entries.firstOrNull { it.wire == value }
    }
}

data class DropDevice(
    val deviceId: String,
    val name: String,
    /** android, windows, macos or linux. */
    val platform: String,
    /** The app said in the last minutes that it can receive. */
    val ready: Boolean,
    /** The app has a LevikDrop version (it registered a key once). */
    val supported: Boolean,
)

data class DropPrompt(
    /** OFFER: the receiver accepts or declines; CONFIRM: the codes must match before files move. */
    val kind: Kind,
    /** Shown on both screens when the other device's key is not known yet ("123 456"). */
    val code: String?,
    val trust: Trust,
) {
    enum class Kind { OFFER, CONFIRM }
}

data class DropFileInfo(val name: String, val size: Long)

data class DropTransferView(
    val id: String,
    val sender: Boolean,
    val peerDeviceId: String,
    val peerName: String,
    val peerPlatform: String?,
    val state: DropState,
    val reason: String?,
    val fileCount: Int,
    val totalBytes: Long,
    /** Names and sizes, when this app knows them (always for the sender). */
    val files: List<DropFileInfo>?,
    val doneBytes: Long,
    val bytesPerSecond: Long?,
    /** How the bytes go right now; null without a session. */
    val path: SessionPath?,
    /** WAITING: the other app is not connected yet; null without a relay connection. */
    val link: LinkStatus?,
    val prompt: DropPrompt?,
    /** Receiver: where the files go. */
    val folder: String?,
    /** Receiver: some files are in place. */
    val saved: Boolean,
    /** The user started or accepted it: it goes on with the app in the background. */
    val held: Boolean,
    val createdAtMs: Long,
    val finishedAtMs: Long?,
    /** Available after the handshake, before the sender confirms and releases the manifest. */
    val verificationCode: String? = null,
)

data class DropQuota(val limitBytes: Long, val usedBytes: Long, val resetsAtMs: Long)

data class DropSnapshot(
    /** The website offers LevikDrop. */
    val available: Boolean = false,
    val loadedAtMs: Long? = null,
    val devices: List<DropDevice> = emptyList(),
    val transfers: List<DropTransferView> = emptyList(),
    val quota: DropQuota? = null,
    val maxFiles: Int = DropWire.MAX_FILES,
    val maxBytes: Long = DropWire.MAX_TOTAL,
    /** Where received files go; null on Android 9 and older until the user picks a folder. */
    val folder: String? = null,
    /** The website's error code of the last failed refresh, "network" or null. */
    val error: String? = null,
) {
    val openTransfer: DropTransferView? get() = transfers.firstOrNull { it.state.open }
}

enum class DropAnswer { ACCEPT, DECLINE, CONFIRM, REJECT }

enum class DropAction { PAUSE, RESUME, CANCEL }

/** What the app tells the user outside its screen. */
sealed class DropNotice {
    abstract val transferId: String

    data class Offer(override val transferId: String, val peerName: String, val names: List<String>) : DropNotice()
    data class Received(override val transferId: String, val peerName: String, val fileCount: Int, val folder: String?) : DropNotice()
}
