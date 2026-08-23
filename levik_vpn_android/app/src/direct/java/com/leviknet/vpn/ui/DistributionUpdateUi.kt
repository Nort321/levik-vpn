package com.leviknet.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.leviknet.vpn.core.update.AppUpdateDto
import com.leviknet.vpn.core.update.UpdateState
import com.leviknet.vpn.ui.theme.LevikDimensions

@Composable
internal fun DistributionUpdateSettingsItem(onCheckForUpdates: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheckForUpdates),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.update_check_btn),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(14.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DistributionUpdateDialog(
    updateState: UpdateState,
    onDownload: (AppUpdateDto) -> Unit,
    onDismiss: () -> Unit,
) {
    if (updateState is UpdateState.Idle) return

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = {
            Text(
                when (updateState) {
                    is UpdateState.Available -> stringResource(R.string.update_available_title)
                    is UpdateState.Downloading -> stringResource(R.string.update_downloading_title)
                    is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_title)
                    is UpdateState.UpToDate -> stringResource(R.string.update_uptodate_title)
                    is UpdateState.Error -> stringResource(R.string.update_error_title)
                    else -> stringResource(R.string.update_title)
                },
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (updateState) {
                    is UpdateState.Available -> {
                        val changelog = updateState.info.changelogRu ?: updateState.info.changelogEn
                        Text(
                            text = stringResource(
                                R.string.update_version_label,
                                updateState.info.latestVersionName,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!changelog.isNullOrBlank()) {
                            Text(
                                text = changelog,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text(
                            text = stringResource(
                                R.string.update_progress_label,
                                updateState.progressPercent,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            progress = { updateState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is UpdateState.ReadyToInstall -> Text(
                        text = stringResource(R.string.update_ready_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is UpdateState.UpToDate -> Text(
                        text = stringResource(R.string.update_uptodate_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is UpdateState.Error -> Text(
                        text = updateState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is UpdateState.Available -> UpdateActionButton(
                    text = stringResource(R.string.update_download_btn),
                    onClick = { onDownload(updateState.info) },
                )
                is UpdateState.ReadyToInstall -> UpdateActionButton(
                    text = stringResource(R.string.update_install_btn),
                    onClick = { onDownload(updateState.info) },
                )
                else -> UpdateActionButton(
                    text = stringResource(R.string.close),
                    onClick = onDismiss,
                )
            }
        },
        dismissButton = {
            if (updateState is UpdateState.Available || updateState is UpdateState.ReadyToInstall) {
                TextButton(
                    onClick = onDismiss,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.height(LevikDimensions.ButtonHeight),
                ) {
                    Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
                }
            }
        },
    )
}

@Composable
private fun UpdateActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.height(LevikDimensions.ButtonHeight),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}
