package com.leviknet.vpn.ui.drop

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leviknet.vpn.R
import com.leviknet.vpn.core.drop.AndroidDropFolders
import com.leviknet.vpn.core.drop.DropAction
import com.leviknet.vpn.core.drop.DropAnswer
import com.leviknet.vpn.core.drop.DropDevice
import com.leviknet.vpn.core.drop.DropFormat
import com.leviknet.vpn.core.drop.DropPrompt
import com.leviknet.vpn.core.drop.DropState
import com.leviknet.vpn.core.drop.DropTransferView
import com.leviknet.vpn.core.drop.DropUserError
import com.leviknet.vpn.core.drop.LinkStatus
import com.leviknet.vpn.core.drop.SessionPath
import com.leviknet.vpn.core.drop.Trust
import com.leviknet.vpn.ui.theme.LevikBlue
import com.leviknet.vpn.ui.theme.LevikGreen

private const val DROP_STALE_MS = 30_000L
private const val SHOWN_NAMES = 5

/** "File transfer" in the profile, right under "My devices", docs/levik-drop.md. */
@Composable
internal fun DropCard(state: DropUiState, controller: DropController) {
    val snapshot = state.snapshot
    LaunchedEffect(Unit) {
        val loadedAt = snapshot.loadedAtMs
        if (loadedAt == null || System.currentTimeMillis() - loadedAt >= DROP_STALE_MS) controller.refresh()
    }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        target?.let { deviceId -> controller.send(deviceId, uris, shared = false) }
        target = null
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) controller.setFolder(tree)
    }
    val busy = snapshot.openTransfer != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.drop_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.drop_caption, snapshot.maxFiles, DropFormat.bytes(LocalContext.current, snapshot.maxBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = controller::refresh) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.drop_refresh),
                        tint = LevikBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            state.message?.let { message ->
                Row(
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dropMessage(message, snapshot.maxFiles, snapshot.maxBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = controller::dismissMessage) { Text(stringResource(R.string.close)) }
                }
            }
            when {
                snapshot.loadedAtMs == null && snapshot.error == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    DropNote(stringResource(R.string.drop_loading))
                }
                snapshot.loadedAtMs == null -> DropNote(dropMessage(DropMessage.Api(snapshot.error.orEmpty()), snapshot.maxFiles, snapshot.maxBytes))
                !snapshot.available -> DropNote(stringResource(R.string.drop_unavailable))
                snapshot.devices.isEmpty() -> DropNote(stringResource(R.string.drop_no_devices))
                else -> snapshot.devices.forEach { device ->
                    DropDeviceRow(
                        device = device,
                        busy = busy,
                        sending = state.sendingTo == device.deviceId,
                        enabled = state.sendingTo == null,
                        onSend = {
                            target = device.deviceId
                            picker.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
            if (snapshot.available) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                DropFolderRow(
                    folder = snapshot.folder,
                    onChange = { folderPicker.launch(null) },
                    onReset = { controller.setFolder(null) },
                )
                snapshot.quota?.let { quota ->
                    val context = LocalContext.current
                    DropNote(stringResource(R.string.drop_quota, DropFormat.bytes(context, quota.usedBytes), DropFormat.bytes(context, quota.limitBytes)))
                }
            }
            if (snapshot.transfers.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(stringResource(R.string.drop_history), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                snapshot.transfers.forEach { transfer -> DropTransferRow(transfer, controller) }
            }
        }
    }
}

@Composable
private fun DropNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun platformLabel(platform: String?): String = when (platform) {
    "android" -> "Android"
    "windows" -> "Windows"
    "macos" -> "macOS"
    "linux" -> "Linux"
    else -> ""
}

@Composable
private fun DropDeviceRow(device: DropDevice, busy: Boolean, sending: Boolean, enabled: Boolean, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = FontWeight.Medium, maxLines = 1)
            val status = when {
                !device.supported -> stringResource(R.string.drop_device_update)
                device.ready && busy -> stringResource(R.string.drop_device_busy)
                device.ready -> stringResource(R.string.drop_device_ready)
                device.platform == "android" -> stringResource(R.string.drop_device_phone_offline)
                else -> stringResource(R.string.drop_device_offline)
            }
            Text(
                "${platformLabel(device.platform)} · $status",
                style = MaterialTheme.typography.bodySmall,
                color = if (device.ready && device.supported && !busy) LevikGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val label = stringResource(R.string.drop_send_to, device.name)
        OutlinedButton(
            onClick = onSend,
            enabled = enabled && device.ready && device.supported && !busy,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Text(stringResource(if (sending) R.string.drop_sending else R.string.drop_send))
        }
    }
}

/** "Downloads/Levik", "Folder “Documents”" or a call to pick one. */
@Composable
private fun folderLabel(folder: String?): String {
    if (folder == null) return stringResource(R.string.drop_folder_none)
    if (folder == AndroidDropFolders.DOWNLOADS) return stringResource(R.string.drop_folder_downloads)
    val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder)) }.getOrNull().orEmpty()
    val name = id.substringAfter(':').substringAfterLast('/').ifEmpty { id }
    return stringResource(R.string.drop_folder_picked, name)
}

@Composable
private fun DropFolderRow(folder: String?, onChange: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.drop_folder_title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(folderLabel(folder), fontWeight = FontWeight.Medium)
        }
        if (folder != null && folder != AndroidDropFolders.DOWNLOADS && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.drop_folder_reset)) }
        }
        TextButton(onClick = onChange) {
            Text(stringResource(if (folder == null) R.string.drop_folder_choose else R.string.drop_folder_change))
        }
    }
}

@Composable
private fun DropTransferRow(transfer: DropTransferView, controller: DropController) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(if (transfer.sender) R.string.drop_to else R.string.drop_from, transfer.peerName),
            fontWeight = FontWeight.Medium,
        )
        val files = transfer.files
        Text(
            if (files != null && files.size <= 3) files.joinToString(", ") { it.name }
            else stringResource(R.string.drop_files_size, DropFormat.fileCount(context, transfer.fileCount), DropFormat.bytes(context, transfer.totalBytes)),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
        if (transfer.state.open) {
            LinearProgressIndicator(
                progress = { if (transfer.totalBytes > 0) (transfer.doneBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            val done = DropFormat.bytes(context, transfer.doneBytes)
            val total = DropFormat.bytes(context, transfer.totalBytes)
            Text(
                transfer.bytesPerSecond?.let { stringResource(R.string.drop_progress_rate, done, total, DropFormat.bytes(context, it)) }
                    ?: stringResource(R.string.drop_progress_line, done, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            dropStatus(transfer),
            style = MaterialTheme.typography.bodySmall,
            color = when (transfer.state) {
                DropState.FAILED -> MaterialTheme.colorScheme.error
                DropState.DONE -> LevikGreen
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!transfer.sender && files == null && transfer.prompt == null) {
            transfer.verificationCode?.let { code ->
                Text(stringResource(R.string.drop_code_description, code), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.drop_code_hint, transfer.peerName), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val prompt = transfer.prompt
            if (prompt != null) {
                Button(onClick = { controller.reopen(transfer.id, prompt.code) }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(if (prompt.kind == DropPrompt.Kind.OFFER) R.string.drop_status_answer else R.string.drop_check_code))
                }
            }
            if (transfer.state == DropState.ACTIVE && prompt == null) {
                TextButton(onClick = { controller.control(transfer.id, DropAction.PAUSE) }) { Text(stringResource(R.string.drop_pause)) }
            }
            if (transfer.state == DropState.PAUSED && transfer.link == null) {
                TextButton(onClick = { controller.control(transfer.id, DropAction.RESUME) }) { Text(stringResource(R.string.drop_resume)) }
            }
            if (transfer.state.open) {
                TextButton(onClick = { controller.control(transfer.id, DropAction.CANCEL) }) { Text(stringResource(R.string.drop_cancel)) }
            }
            if (!transfer.sender && transfer.saved && transfer.folder == AndroidDropFolders.DOWNLOADS) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.drop_show)) }
            }
        }
    }
}

@Composable
private fun failure(reason: String?): String = stringResource(
    when (reason) {
        "source_changed" -> R.string.drop_failure_source_changed
        "integrity" -> R.string.drop_failure_integrity
        "storage_full" -> R.string.drop_failure_storage_full
        "storage_error" -> R.string.drop_failure_storage_error
        "source_unreadable" -> R.string.drop_failure_source_unreadable
        "protocol" -> R.string.drop_failure_protocol
        "key_rejected" -> R.string.drop_failure_key_rejected
        else -> R.string.drop_failure_other
    },
)

/** One line under the transfer, the same as the desktop apps show. */
@Composable
internal fun dropStatus(transfer: DropTransferView): String {
    val peer = transfer.peerName
    return when (transfer.state) {
        DropState.OFFERED -> when {
            transfer.sender && transfer.prompt?.kind == DropPrompt.Kind.CONFIRM -> stringResource(R.string.drop_status_check_code)
            transfer.sender && transfer.link == LinkStatus.SESSION -> stringResource(R.string.drop_status_waiting_accept, peer)
            transfer.sender -> stringResource(R.string.drop_status_waiting_peer, peer)
            transfer.prompt != null -> stringResource(R.string.drop_status_answer)
            else -> stringResource(R.string.drop_status_manifest)
        }
        DropState.ACTIVE -> when {
            transfer.prompt?.kind == DropPrompt.Kind.CONFIRM -> stringResource(R.string.drop_status_check_code)
            transfer.link != LinkStatus.SESSION && transfer.peerPlatform == "android" -> stringResource(R.string.drop_status_waiting_phone, peer)
            transfer.link != LinkStatus.SESSION -> stringResource(R.string.drop_status_waiting_resume, peer)
            transfer.path == SessionPath.LAN -> stringResource(R.string.drop_status_lan)
            else -> stringResource(R.string.drop_status_relay)
        }
        DropState.PAUSED -> stringResource(if (transfer.reason == "quota") R.string.drop_status_paused_quota else R.string.drop_status_paused)
        DropState.DONE -> stringResource(if (transfer.sender) R.string.drop_status_delivered else R.string.drop_status_received)
        DropState.DECLINED -> if (transfer.sender) stringResource(R.string.drop_status_declined_by_peer, peer) else stringResource(R.string.drop_status_declined_self)
        DropState.CANCELLED -> stringResource(if (transfer.reason == "device_revoked") R.string.drop_status_cancelled_revoked else R.string.drop_status_cancelled)
        DropState.FAILED -> stringResource(R.string.drop_status_failed, failure(transfer.reason))
        DropState.EXPIRED -> stringResource(R.string.drop_status_expired)
    }
}

@Composable
private fun dropMessage(message: DropMessage, maxFiles: Int, maxBytes: Long): String {
    val context = LocalContext.current
    return when (message) {
        is DropMessage.User -> when (message.kind) {
            DropUserError.Kind.SIGNED_OUT -> stringResource(R.string.drop_error_signed_out)
            DropUserError.Kind.BUSY -> stringResource(R.string.drop_error_busy)
            DropUserError.Kind.NOTHING -> stringResource(R.string.drop_error_nothing)
            DropUserError.Kind.TOO_MANY_FILES -> stringResource(R.string.drop_error_too_many, maxFiles)
            DropUserError.Kind.TOO_LARGE -> stringResource(R.string.drop_error_too_large, DropFormat.bytes(context, maxBytes))
            DropUserError.Kind.UNREADABLE -> message.detail?.let { stringResource(R.string.drop_error_unreadable, it) }
                ?: stringResource(R.string.drop_error_unreadable_any)
            DropUserError.Kind.NOT_A_FILE -> stringResource(R.string.drop_error_not_a_file, message.detail.orEmpty())
            DropUserError.Kind.BAD_RESPONSE -> stringResource(R.string.drop_error_bad_response)
            DropUserError.Kind.STALE -> stringResource(R.string.drop_error_stale)
            DropUserError.Kind.NO_FOLDER -> stringResource(R.string.drop_error_no_folder)
        }
        is DropMessage.Api -> stringResource(
            when (message.code) {
                "subscription_required" -> R.string.drop_error_subscription
                "device_not_ready" -> R.string.drop_error_not_ready
                "device_busy" -> R.string.drop_error_device_busy
                "quota_exceeded" -> R.string.drop_error_quota
                "device_not_found" -> R.string.drop_error_device_gone
                "drop_unavailable" -> R.string.drop_unavailable
                "rate_limited" -> R.string.drop_error_rate_limited
                "unauthorized" -> R.string.drop_error_signed_out
                "network" -> R.string.drop_error_network
                else -> R.string.drop_error_other
            },
        )
    }
}

/**
 * Dialogs that may come up on any screen: an offer or a code to compare, a
 * folder for Android 9 and older, files shared from another app.
 */
@Composable
internal fun DropDialogs(state: DropUiState, controller: DropController, signedIn: Boolean) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) controller.setFolder(tree) else controller.cancelFolder()
    }
    LaunchedEffect(state.folderFor) {
        if (state.folderFor != null) folderPicker.launch(null)
    }
    val prompted = state.snapshot.transfers.firstOrNull { transfer ->
        val prompt = transfer.prompt
        prompt != null && controller.promptKey(transfer.id, prompt.code) !in state.later
    }
    if (prompted != null) {
        DropPromptDialog(prompted, state.answering, controller)
    } else if (state.shared.isNotEmpty()) {
        DropShareDialog(state, controller, signedIn)
    }
}

@Composable
private fun DropPromptDialog(transfer: DropTransferView, answering: Boolean, controller: DropController) {
    val prompt = transfer.prompt ?: return
    val context = LocalContext.current
    val offer = prompt.kind == DropPrompt.Kind.OFFER
    AlertDialog(
        onDismissRequest = { controller.later(transfer.id, prompt.code) },
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    stringResource(if (offer) R.string.drop_offer_title else R.string.drop_confirm_title, transfer.peerName),
                    fontWeight = FontWeight.Bold,
                )
                if (!offer) {
                    Text(
                        stringResource(if (transfer.sender) R.string.drop_confirm_sending else R.string.drop_confirm_receiving, transfer.peerName),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offer) {
                    val files = transfer.files.orEmpty()
                    files.take(SHOWN_NAMES).forEach { file ->
                        Text("${file.name} · ${DropFormat.bytes(context, file.size)}", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                    if (files.size > SHOWN_NAMES) {
                        Text(stringResource(R.string.drop_offer_more, files.size - SHOWN_NAMES), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.drop_files_size, DropFormat.fileCount(context, transfer.fileCount), DropFormat.bytes(context, transfer.totalBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (prompt.trust == Trust.CHANGED) {
                    Text(
                        stringResource(R.string.drop_key_changed, transfer.peerName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                prompt.code?.let { code ->
                    Text(
                        stringResource(R.string.drop_code_hint, transfer.peerName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val description = stringResource(R.string.drop_code_description, code.replace(" ", ""))
                    Text(
                        code,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = description },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { controller.answer(transfer.id, if (offer) DropAnswer.ACCEPT else DropAnswer.CONFIRM, prompt.code) },
                enabled = !answering,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(if (offer) R.string.drop_accept else R.string.drop_codes_match))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { controller.later(transfer.id, prompt.code) }) { Text(stringResource(R.string.drop_later)) }
                TextButton(
                    onClick = { controller.answer(transfer.id, if (offer) DropAnswer.DECLINE else DropAnswer.REJECT, prompt.code) },
                    enabled = !answering,
                ) {
                    Text(stringResource(if (offer) R.string.drop_decline else R.string.drop_codes_differ))
                }
            }
        },
    )
}

@Composable
private fun DropShareDialog(state: DropUiState, controller: DropController, signedIn: Boolean) {
    val context = LocalContext.current
    val snapshot = state.snapshot
    val busy = snapshot.openTransfer != null
    AlertDialog(
        onDismissRequest = controller::dismissShared,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.drop_share_title, DropFormat.fileCount(context, state.shared.size)), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !signedIn -> DropNote(stringResource(R.string.drop_share_signed_out))
                    snapshot.loadedAtMs == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        DropNote(stringResource(R.string.drop_loading))
                    }
                    !snapshot.available -> DropNote(stringResource(R.string.drop_unavailable))
                    snapshot.devices.isEmpty() -> DropNote(stringResource(R.string.drop_no_devices))
                    else -> {
                        DropNote(stringResource(R.string.drop_share_body))
                        snapshot.devices.forEach { device ->
                            DropDeviceRow(
                                device = device,
                                busy = busy,
                                sending = state.sendingTo == device.deviceId,
                                enabled = state.sendingTo == null,
                                onSend = { controller.send(device.deviceId, state.shared, shared = true) },
                            )
                        }
                    }
                }
                state.message?.let { message ->
                    Text(dropMessage(message, snapshot.maxFiles, snapshot.maxBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = controller::dismissShared) { Text(stringResource(R.string.close)) } },
    )
}
