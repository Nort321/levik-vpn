package com.leviknet.vpn.core.platform

import android.content.Context
import com.leviknet.vpn.R
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.telemetry.SessionTrigger
import com.leviknet.vpn.core.update.AppUpdateManager
import com.leviknet.vpn.core.update.UpdateState
import com.leviknet.vpn.data.AppRepository
import com.leviknet.vpn.data.AppSettings
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnController
import java.security.PrivateKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a command needs from the rest of the app; each is the same path the user's own tap takes. */
interface RemoteActions {
    /** Switches a running connection to the fastest server of its kind. */
    suspend fun moveToFastestServer()

    /** Refreshes the account and the tunnel profile. */
    suspend fun refreshProfile()

    /** Encrypts a diagnostics report into a one-time note and returns its link. */
    suspend fun diagnosticsNote(): String
}

/**
 * The remote, docs/app-platform.md "Remote": runs the commands sent to this
 * phone by the user's other apps or the website, and sends commands to them.
 * There is no command to disconnect; anything unknown is answered "unsupported".
 */
class RemoteControl(
    private val context: Context,
    private val settings: AppSettings,
    private val repository: AppRepository,
    private val vpnController: VpnController,
    private val updateManager: AppUpdateManager?,
    private val actions: RemoteActions,
    private val scope: CoroutineScope,
    private val notify: (notificationId: Int, title: String, body: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val commandsMutex = Mutex()
    private val refreshMutex = Mutex()
    private val handled = LinkedHashSet<String>()
    private val replyKeys = mutableMapOf<String, PrivateKey>()
    private var watchedUntilMs = 0L

    private val mutableSnapshot = MutableStateFlow(RemoteSnapshot())
    val snapshot: StateFlow<RemoteSnapshot> = mutableSnapshot.asStateFlow()

    fun onEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.READY, LiveEvent.RESYNC, LiveEvent.COMMANDS -> {
                runCommands()
                refreshIfWatched()
            }
            LiveEvent.DEVICES, LiveEvent.PRESENCE -> refreshIfWatched()
            else -> Unit
        }
    }

    /** Loads the account's apps; while [watch] lasts, events keep the list current. */
    suspend fun refresh(watch: Boolean = true) = refreshMutex.withLock {
        if (repository.session.value != SessionStatus.Authenticated) return@withLock
        if (watch) watchedUntilMs = clock() + WATCH_MS
        val response = repository.remoteDevices()
        val commands = RemoteCommands.parseIssued(response["commands"])
        val notes = mutableSnapshot.value.notes.toMutableMap()
        for (command in commands) {
            if (!command.state.final) continue
            val key = synchronized(replyKeys) { replyKeys.remove(command.id) } ?: continue
            val link = command.detail?.takeIf { command.state == RemoteCommandState.DONE }?.let { RemoteCommands.open(key, it) }
            if (link != null) notes[command.id] = link
        }
        mutableSnapshot.value = RemoteSnapshot(RemoteCommands.parseDevices(response["devices"]), commands, notes, clock())
    }

    /** Sends a command to another app of the account. */
    suspend fun send(deviceId: String, command: RemoteCommand) {
        require(mutableSnapshot.value.devices.any { it.deviceId == deviceId && it.remote && !it.current }) {
            "The device does not take remote commands"
        }
        val reply = if (command == RemoteCommand.COLLECT_DIAGNOSTICS) RemoteCommands.createReplyKey() else null
        val response = repository.issueCommand(deviceId, command.wire, reply?.first)
        val issued = RemoteCommands.parseIssued(kotlinx.serialization.json.JsonArray(listOfNotNull(response["command"]))).firstOrNull()
        if (issued != null && reply != null) synchronized(replyKeys) { replyKeys[issued.id] = reply.second }
        refresh(watch = false)
    }

    /** A diagnostics link opened with this phone's key; nothing else is returned. */
    fun note(commandId: String): String? = mutableSnapshot.value.notes[commandId]

    fun forget() {
        synchronized(replyKeys) { replyKeys.clear() }
        synchronized(handled) { handled.clear() }
        watchedUntilMs = 0L
        mutableSnapshot.value = RemoteSnapshot()
    }

    private fun refreshIfWatched() {
        if (clock() >= watchedUntilMs) return
        scope.launch { quietly("Remote list") { refresh(watch = false) } }
    }

    private fun runCommands() {
        scope.launch {
            commandsMutex.withLock { quietly("Remote commands") { takeAndRun() } }
        }
    }

    private suspend fun takeAndRun() {
        if (repository.session.value != SessionStatus.Authenticated) return
        for (command in RemoteCommands.parseDelivered(repository.takeCommands()["commands"])) {
            val fresh = synchronized(handled) {
                handled.add(command.id).also {
                    if (handled.size > MAX_HANDLED) handled.remove(handled.first())
                }
            }
            if (!fresh) continue
            val outcome = execute(command)
            quietly("Remote result") { repository.commandResult(command.id, outcome.result.wire, outcome.detail) }
            if (command.fromFamily && outcome.result == CommandResult.DONE) tellFamilyHelped(command)
        }
    }

    internal suspend fun execute(command: DeliveredCommand): CommandOutcome {
        // Only the fixed list runs; a disconnect or anything else is refused here too.
        val known = RemoteCommand.of(command.command) ?: return CommandOutcome.UNSUPPORTED
        // The family owner only repairs; the server holds the same line.
        if (command.fromFamily && known !in RemoteCommands.FAMILY_COMMANDS) return CommandOutcome.UNSUPPORTED
        AppLogger.i(TAG, "Remote command: ${known.wire}")
        return try {
            when (known) {
                RemoteCommand.CONNECT -> connect()
                RemoteCommand.AUTO_SERVER -> {
                    if (!settings.automaticServer.value) settings.setAutomaticServer(true)
                    if (vpnController.state.value.state == VpnConnectionState.CONNECTED) actions.moveToFastestServer()
                    CommandOutcome.DONE
                }
                RemoteCommand.REFRESH_PROFILE -> {
                    actions.refreshProfile()
                    CommandOutcome.DONE
                }
                RemoteCommand.CHECK_UPDATE -> checkUpdate()
                RemoteCommand.COLLECT_DIAGNOSTICS -> {
                    val replyKey = command.replyKey ?: return CommandOutcome.FAILED
                    val link = actions.diagnosticsNote()
                    notify(
                        NOTIFICATION_ID,
                        context.getString(R.string.remote_diagnostics_title),
                        context.getString(R.string.remote_diagnostics_body),
                    )
                    CommandOutcome(CommandResult.DONE, RemoteCommands.seal(replyKey, link))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w(TAG, "Remote command failed: ${error.javaClass.simpleName}")
            CommandOutcome.FAILED
        }
    }

    /** The member always learns what the family owner did on their phone. */
    private fun tellFamilyHelped(command: DeliveredCommand) {
        val body = when (RemoteCommand.of(command.command)) {
            RemoteCommand.REFRESH_PROFILE -> R.string.family_helped_profile
            RemoteCommand.AUTO_SERVER -> R.string.family_helped_server
            RemoteCommand.CHECK_UPDATE -> R.string.family_helped_update
            else -> return
        }
        notify(FAMILY_NOTIFICATION_ID, context.getString(R.string.family_helped_title), context.getString(body))
    }

    private suspend fun connect(): CommandOutcome = when (vpnController.state.value.state) {
        VpnConnectionState.CONNECTED,
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING,
        -> CommandOutcome(CommandResult.DONE, RemoteCommands.ALREADY_CONNECTED)
        VpnConnectionState.PAUSED -> {
            vpnController.resume()
            CommandOutcome.DONE
        }
        VpnConnectionState.STOPPING -> CommandOutcome(CommandResult.FAILED, RemoteCommands.BUSY)
        VpnConnectionState.DISCONNECTED,
        VpnConnectionState.ERROR,
        VpnConnectionState.LOCKDOWN,
        -> if (!vpnController.hasDisclosureConsent() || vpnController.permissionIntent() != null) {
            // Android asks for the VPN permission on the phone itself.
            CommandOutcome(CommandResult.FAILED, RemoteCommands.VPN_PERMISSION)
        } else {
            vpnController.connect(SessionTrigger.USER)
            notify(
                NOTIFICATION_ID,
                context.getString(R.string.remote_connected_title),
                context.getString(R.string.remote_connected_body),
            )
            CommandOutcome.DONE
        }
    }

    private suspend fun checkUpdate(): CommandOutcome {
        val manager = updateManager ?: return CommandOutcome.UNSUPPORTED
        // Not silent: the app offers the update the next time it is on the screen.
        val update = manager.checkForUpdates(silent = false)
        return when {
            update != null -> CommandOutcome(CommandResult.DONE, RemoteCommands.availableDetail(update.latestVersionName))
            manager.state.value == UpdateState.UpToDate -> CommandOutcome(CommandResult.DONE, RemoteCommands.UP_TO_DATE)
            else -> CommandOutcome(CommandResult.FAILED, RemoteCommands.UPDATE_CHECK_FAILED)
        }
    }

    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.d(TAG, "$what failed: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "RemoteControl"
        const val NOTIFICATION_ID = 5400
        const val FAMILY_NOTIFICATION_ID = 5401
        const val WATCH_MS = 10 * 60 * 1_000L
        const val MAX_HANDLED = 200
    }
}
