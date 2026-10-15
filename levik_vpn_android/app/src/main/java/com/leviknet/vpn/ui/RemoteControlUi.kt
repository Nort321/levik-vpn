package com.leviknet.vpn.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R
import com.leviknet.vpn.core.platform.IssuedCommand
import com.leviknet.vpn.core.platform.RemoteCommand
import com.leviknet.vpn.core.platform.RemoteCommandState
import com.leviknet.vpn.core.platform.RemoteCommands
import com.leviknet.vpn.core.platform.RemoteDevice
import com.leviknet.vpn.core.platform.RemotePlatform
import com.leviknet.vpn.core.platform.RemoteSnapshot
import com.leviknet.vpn.ui.theme.LevikBlue
import com.leviknet.vpn.ui.theme.LevikGreen

private const val REMOTE_STALE_MS = 60_000L

/** "My devices" in the profile: the account's other apps and their remote, docs/app-platform.md "Remote". */
@Composable
internal fun RemoteDevicesCard(
    remote: RemoteSnapshot,
    loading: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    LaunchedEffect(Unit) {
        val loadedAt = remote.loadedAtMs
        if (loadedAt == null || System.currentTimeMillis() - loadedAt >= REMOTE_STALE_MS) onRefresh()
    }
    val others = remote.devices.filterNot(RemoteDevice::current)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.remote_devices_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.remote_devices_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.remote_devices_refresh),
                        tint = LevikBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            when {
                remote.loadedAtMs == null && loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    RemoteNote(R.string.remote_devices_loading)
                }
                remote.loadedAtMs == null && failed -> RemoteNote(R.string.remote_devices_error)
                remote.loadedAtMs != null && others.isEmpty() -> RemoteNote(R.string.remote_devices_empty)
                else -> others.forEach { device -> RemoteDeviceRow(device, onOpen) }
            }
        }
    }
}

@Composable
private fun RemoteNote(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RemoteDeviceRow(device: RemoteDevice, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = FontWeight.Medium, maxLines = 1)
            val meta = device.appVersion?.let { stringResource(R.string.remote_device_meta, device.platform.label(), it) }
                ?: device.platform.label()
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                presence(device),
                style = MaterialTheme.typography.bodySmall,
                color = if (device.online) LevikGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (device.remote) {
            val label = stringResource(R.string.remote_open_for, device.name)
            OutlinedButton(
                onClick = { onOpen(device.deviceId) },
                modifier = Modifier.semantics { contentDescription = label },
            ) { Text(stringResource(R.string.remote_open)) }
        } else {
            Text(
                stringResource(R.string.remote_update_app),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

@Composable
private fun presence(device: RemoteDevice): String = when {
    !device.online -> stringResource(R.string.remote_offline)
    !device.vpnConnected -> stringResource(R.string.remote_online_vpn_off)
    device.country != null -> stringResource(R.string.remote_online_vpn_on_country, device.country)
    else -> stringResource(R.string.remote_online_vpn_on)
}

private fun RemotePlatform.label(): String = when (this) {
    RemotePlatform.ANDROID -> "Android"
    RemotePlatform.WINDOWS -> "Windows"
    RemotePlatform.MACOS -> "macOS"
    RemotePlatform.LINUX -> "Linux"
}

@StringRes
private fun RemoteCommand.label(): Int = when (this) {
    RemoteCommand.CONNECT -> R.string.remote_command_connect
    RemoteCommand.AUTO_SERVER -> R.string.remote_command_auto_server
    RemoteCommand.REFRESH_PROFILE -> R.string.remote_command_refresh_profile
    RemoteCommand.CHECK_UPDATE -> R.string.remote_command_check_update
    RemoteCommand.COLLECT_DIAGNOSTICS -> R.string.remote_command_collect_diagnostics
}

/** The remote for one app: five commands, and never a disconnect. */
@Composable
internal fun RemoteControlDialog(
    device: RemoteDevice,
    latest: IssuedCommand?,
    note: String?,
    sending: RemoteCommand?,
    error: RemoteSendError?,
    onSend: (RemoteCommand) -> Unit,
    onOpenNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val waiting = latest != null && !latest.state.final
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(stringResource(R.string.remote_open_for, device.name), fontWeight = FontWeight.Bold)
                Text(
                    "${device.platform.label()} · ${presence(device)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteCommand.entries.forEach { command ->
                    OutlinedButton(
                        onClick = { onSend(command) },
                        enabled = sending == null && !waiting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(if (sending == command) R.string.remote_sending else command.label()))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (waiting) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        status(device, latest, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (note != null && latest != null) {
                Button(onClick = { onOpenNote(latest.id) }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.remote_open_note))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun status(device: RemoteDevice, command: IssuedCommand?, error: RemoteSendError?): String {
    if (error != null) {
        return stringResource(
            when (error) {
                RemoteSendError.RATE_LIMITED -> R.string.remote_rate_limited
                RemoteSendError.DEVICE_GONE -> R.string.remote_device_gone
                RemoteSendError.FAILED -> R.string.remote_send_failed
            },
        )
    }
    if (command == null) {
        return stringResource(if (device.platform == RemotePlatform.ANDROID) R.string.remote_idle_phone else R.string.remote_idle_computer)
    }
    val detail = command.detail
    val text = when (command.state) {
        RemoteCommandState.PENDING -> stringResource(
            if (device.platform == RemotePlatform.ANDROID && !device.online) R.string.remote_state_pending_phone else R.string.remote_state_pending,
        )
        RemoteCommandState.DELIVERED -> stringResource(R.string.remote_state_delivered)
        RemoteCommandState.DONE -> when {
            command.command == RemoteCommand.CHECK_UPDATE && detail?.startsWith(RemoteCommands.AVAILABLE_PREFIX) == true ->
                stringResource(R.string.remote_state_update_available, detail.removePrefix(RemoteCommands.AVAILABLE_PREFIX))
            command.command == RemoteCommand.CHECK_UPDATE -> stringResource(R.string.remote_state_up_to_date)
            command.command == RemoteCommand.COLLECT_DIAGNOSTICS -> stringResource(R.string.remote_state_diagnostics_done)
            else -> stringResource(R.string.remote_state_done)
        }
        RemoteCommandState.FAILED -> stringResource(
            if (detail == RemoteCommands.VPN_PERMISSION) R.string.remote_state_vpn_permission else R.string.remote_state_failed,
        )
        RemoteCommandState.UNSUPPORTED -> stringResource(R.string.remote_state_unsupported)
        RemoteCommandState.EXPIRED -> stringResource(R.string.remote_state_expired)
    }
    return stringResource(R.string.remote_status_line, stringResource(command.command.label()), text)
}
