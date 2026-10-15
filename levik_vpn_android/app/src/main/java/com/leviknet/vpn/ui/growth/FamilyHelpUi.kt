package com.leviknet.vpn.ui.growth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R
import com.leviknet.vpn.core.network.FamilyHelpCommand
import com.leviknet.vpn.core.network.FamilyHelpDevice
import com.leviknet.vpn.core.network.FamilyHelpResponse
import com.leviknet.vpn.core.platform.RemoteCommands
import com.leviknet.vpn.ui.theme.LevikDimensions
import com.leviknet.vpn.ui.theme.LevikGreen

/** The member's own switch: lets the family owner help with their apps. */
@Composable
internal fun FamilyHelpSwitch(allowed: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    val state = stringResource(if (allowed) R.string.family_help_state_on else R.string.family_help_state_off)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = allowed, enabled = !busy, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.family_help_switch), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.family_help_switch_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row is the control; the switch only shows its state.
        Switch(checked = allowed, onCheckedChange = null, enabled = !busy)
    }
}

/** For the owner: members who allowed help and three repairs for each of their apps. */
@Composable
internal fun FamilyHelpSection(
    help: FamilyHelpResponse,
    sending: String?,
    error: String?,
    onSend: (memberId: Long, deviceRef: String, command: String) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.family_help_title),
        body = stringResource(R.string.family_help_body),
    ) {
        if (help.members.isEmpty()) {
            HelpNote(R.string.family_help_empty)
        }
        help.members.forEach { member ->
            Text(member.name, fontWeight = FontWeight.SemiBold)
            when {
                !member.linked -> HelpNote(R.string.family_help_not_linked)
                member.devices.isEmpty() -> HelpNote(R.string.family_help_no_apps)
                else -> member.devices.forEach { device ->
                    HelpDevice(
                        device = device,
                        latest = help.latestFor(device.ref),
                        sending = sending,
                        onSend = { command -> onSend(member.memberId, device.ref, command) },
                    )
                }
            }
        }
        error?.let { code ->
            Text(
                stringResource(helpErrorText(code)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun HelpNote(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HelpDevice(
    device: FamilyHelpDevice,
    latest: FamilyHelpCommand?,
    sending: String?,
    onSend: (String) -> Unit,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(device.name, fontWeight = FontWeight.Medium)
            Text(
                "${platformLabel(device.platform)} · ${stringResource(presenceText(device))}",
                style = MaterialTheme.typography.bodySmall,
                color = if (device.online) LevikGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!device.remote) {
                HelpNote(R.string.family_help_update_app)
                return@Column
            }
            val waiting = latest != null && !latest.final
            GrowthController.HELP_COMMANDS.forEach { command ->
                OutlinedButton(
                    onClick = { onSend(command) },
                    enabled = sending == null && !waiting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(if (sending == device.ref) R.string.remote_sending else commandLabel(command)))
                }
            }
            latest?.let { command ->
                Text(
                    statusText(command, device),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

private fun platformLabel(platform: String): String = when (platform) {
    "android" -> "Android"
    "windows" -> "Windows"
    "macos" -> "macOS"
    "linux" -> "Linux"
    else -> "Levik VPN"
}

@StringRes
private fun presenceText(device: FamilyHelpDevice): Int = when {
    !device.online -> R.string.remote_offline
    device.vpnConnected -> R.string.remote_online_vpn_on
    else -> R.string.remote_online_vpn_off
}

@StringRes
private fun commandLabel(command: String): Int = when (command) {
    "refresh_profile" -> R.string.remote_command_refresh_profile
    "auto_server" -> R.string.remote_command_auto_server
    else -> R.string.remote_command_check_update
}

@Composable
private fun statusText(command: FamilyHelpCommand, device: FamilyHelpDevice): String {
    val detail = command.detail
    val text = when (command.state) {
        "pending" -> stringResource(
            if (device.platform == "android" && !device.online) R.string.remote_state_pending_phone else R.string.remote_state_pending,
        )
        "delivered" -> stringResource(R.string.remote_state_delivered)
        "done" -> when {
            command.command == "check_update" && detail?.startsWith(RemoteCommands.AVAILABLE_PREFIX) == true ->
                stringResource(R.string.remote_state_update_available, detail.removePrefix(RemoteCommands.AVAILABLE_PREFIX))
            command.command == "check_update" -> stringResource(R.string.remote_state_up_to_date)
            else -> stringResource(R.string.remote_state_done)
        }
        "failed" -> stringResource(R.string.remote_state_failed)
        "unsupported" -> stringResource(R.string.remote_state_unsupported)
        else -> stringResource(R.string.remote_state_expired)
    }
    return stringResource(R.string.remote_status_line, stringResource(commandLabel(command.command)), text)
}

@StringRes
private fun helpErrorText(code: String): Int = when (code) {
    "rate_limited" -> R.string.remote_rate_limited
    "device_not_found" -> R.string.family_help_gone
    else -> R.string.remote_send_failed
}
