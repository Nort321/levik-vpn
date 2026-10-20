package com.leviknet.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R
import com.leviknet.vpn.ui.theme.LevikBlue
import com.leviknet.vpn.ui.theme.LevikDimensions
import com.leviknet.vpn.vpn.GameRuntimeStatus
import com.leviknet.vpn.vpn.GameServerConfig
import kotlin.math.roundToInt

@Composable
internal fun GameSettingsRow(selectedCount: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.game_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (selectedCount == 0) {
                        stringResource(R.string.game_no_games)
                    } else {
                        stringResource(R.string.game_games_count, selectedCount)
                    },
                    color = if (selectedCount == 0) LevikBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Games and the exit for Levik Game; [status] is the live sidecar status while Game is connected. */
@Composable
internal fun GameSettingsDialog(
    config: GameServerConfig,
    selectedCount: Int,
    exitId: String?,
    status: GameRuntimeStatus?,
    onSelectApps: () -> Unit,
    onExitSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // A node with no link of its own is never an exit; disabled nodes are already gone from the routes.
    val exits = config.nodes.filter { node ->
        node.state != "disabled" && config.routes.any { it.nodeId == node.nodeId }
    }
    val selectedExit = exitId?.takeIf { id -> exits.any { it.nodeId == id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.game_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.game_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status != null) {
                    GameStatusText(status)
                }
                Button(
                    onClick = onSelectApps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.game_select_apps, selectedCount), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.game_exit_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                GameExitOption(
                    label = stringResource(R.string.game_exit_auto),
                    selected = selectedExit == null,
                    onClick = { onExitSelected(null) },
                )
                exits.forEach { node ->
                    val name = listOfNotNull(node.name, node.city?.takeIf(String::isNotBlank)).joinToString(" · ")
                    GameExitOption(
                        label = if (node.state == "draining") stringResource(R.string.game_exit_draining, name) else name,
                        selected = selectedExit == node.nodeId,
                        onClick = { onExitSelected(node.nodeId) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun GameExitOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** One line on how Levik Game carries the games right now. */
@Composable
internal fun GameStatusText(status: GameRuntimeStatus, modifier: Modifier = Modifier) {
    val exit = status.exitName?.takeIf(String::isNotBlank)
    val latency = status.latencyMs?.takeIf { it.isFinite() && it >= 0 }?.roundToInt()
    val text = when {
        status.auth == "invalid" -> stringResource(R.string.game_auth_invalid)
        status.auth == "limit" -> stringResource(R.string.game_auth_limit)
        exit == null || latency == null || status.state == "unavailable" -> stringResource(R.string.game_status_searching)
        status.state == "protected" -> stringResource(R.string.game_status_protected, exit, latency)
        else -> stringResource(R.string.game_status_single, exit, latency)
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (status.auth == "invalid" || status.auth == "limit") {
            MaterialTheme.colorScheme.error
        } else {
            LevikBlue
        },
    )
}
