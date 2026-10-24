package com.leviknet.vpn.ui.drop

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.leviknet.vpn.core.drop.DropAction
import com.leviknet.vpn.core.drop.DropAnswer
import com.leviknet.vpn.core.drop.DropEngine
import com.leviknet.vpn.core.drop.DropSnapshot
import com.leviknet.vpn.core.drop.DropTransferService
import com.leviknet.vpn.core.drop.DropUserError
import com.leviknet.vpn.core.drop.RepositoryDropApi
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.ApiException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What went wrong with the user's last tap; the card shows it until dismissed. */
sealed class DropMessage {
    data class User(val kind: DropUserError.Kind, val detail: String?) : DropMessage()

    /** The website's error code, "network" or "unknown". */
    data class Api(val code: String) : DropMessage()
}

data class DropUiState(
    val snapshot: DropSnapshot = DropSnapshot(),
    /** The device files are being offered to right now. */
    val sendingTo: String? = null,
    val message: DropMessage? = null,
    /** Files another app shared, waiting for the user to pick a device. */
    val shared: List<Uri> = emptyList(),
    val answering: Boolean = false,
    /** Prompts the user put off with "Later" (transfer id and code). */
    val later: Set<String> = emptySet(),
    /** An offer waits for a folder before it can be accepted (Android 9 and older). */
    val folderFor: Pair<String, String?>? = null,
)

/**
 * LevikDrop on the phone's screen (Direct builds), docs/levik-drop.md: the
 * profile card, the code dialog and files shared to the app.
 */
class DropController(
    private val engine: DropEngine,
    private val scope: CoroutineScope,
    private val context: Context,
    private val isAuthenticated: () -> Boolean,
) {
    private val local = MutableStateFlow(DropUiState())

    val state: StateFlow<DropUiState> = combine(engine.snapshot, local) { snapshot, ui -> ui.copy(snapshot = snapshot) }
        .stateIn(scope, SharingStarted.Eagerly, DropUiState())

    fun refresh() {
        if (isAuthenticated()) scope.launch { engine.refreshNow() }
    }

    /** Files picked in the system picker ([shared] false) or shared from another app. */
    fun send(deviceId: String, uris: List<Uri>, shared: Boolean) {
        if (uris.isEmpty() || local.value.sendingTo != null) return
        if (!shared) {
            // Picked documents stay readable after a restart, so a paused transfer can go on.
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        local.update { it.copy(sendingTo = deviceId, message = null) }
        scope.launch {
            val message = attempt { engine.send(deviceId, uris.map(Uri::toString)) }
            // The service holds the read grant of shared files while they go.
            if (message == null) DropTransferService.start(context, if (shared) uris else emptyList())
            local.update {
                it.copy(sendingTo = null, message = message, shared = if (message == null && shared) emptyList() else it.shared)
            }
        }
    }

    fun openShared(uris: List<Uri>) {
        local.update { it.copy(shared = uris.distinct(), message = null) }
        refresh()
    }

    fun dismissShared() = local.update { it.copy(shared = emptyList()) }

    fun answer(id: String, answer: DropAnswer, code: String?) {
        if (local.value.answering) return
        local.update { it.copy(answering = true, message = null) }
        scope.launch {
            val message = attempt { engine.answer(id, answer, code) }
            local.update {
                val noFolder = (message as? DropMessage.User)?.kind == DropUserError.Kind.NO_FOLDER
                it.copy(
                    answering = false,
                    message = message.takeUnless { noFolder },
                    folderFor = if (noFolder) id to code else it.folderFor,
                )
            }
        }
    }

    /** "Later": the dialog goes until the user opens it from the transfer. */
    fun later(id: String, code: String?) = local.update { it.copy(later = it.later + promptKey(id, code)) }

    fun reopen(id: String, code: String?) = local.update { it.copy(later = it.later - promptKey(id, code)) }

    fun promptKey(id: String, code: String?): String = "$id:${code.orEmpty()}"

    fun control(id: String, action: DropAction) {
        scope.launch {
            val message = attempt { engine.control(id, action) }
            if (action == DropAction.RESUME && message == null) DropTransferService.start(context)
            local.update { it.copy(message = message) }
        }
    }

    /** A folder from the system picker, or null for Downloads/Levik. */
    fun setFolder(tree: Uri?) {
        scope.launch {
            if (tree != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val granted = runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }.isSuccess
                if (!granted) {
                    local.update { it.copy(message = DropMessage.User(DropUserError.Kind.NO_FOLDER, null), folderFor = null) }
                    return@launch
                }
            }
            engine.setFolder(tree?.toString())
            // An offer that waited for the folder is accepted now.
            val waiting = local.value.folderFor
            local.update { it.copy(folderFor = null) }
            if (tree != null && waiting != null) answer(waiting.first, DropAnswer.ACCEPT, waiting.second)
        }
    }

    fun cancelFolder() = local.update { it.copy(folderFor = null) }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private suspend fun attempt(block: suspend () -> Unit): DropMessage? = try {
        block()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: DropUserError) {
        DropMessage.User(error.kind, error.detail)
    } catch (error: Exception) {
        AppLogger.w(TAG, "LevikDrop request failed: ${error.javaClass.simpleName}")
        DropMessage.Api(RepositoryDropApi.errorCode(error) ?: if (error is ApiException.Network) DropEngine.NETWORK else UNKNOWN)
    }

    private companion object {
        const val TAG = "LevikDrop"
        const val UNKNOWN = "unknown"
    }
}
