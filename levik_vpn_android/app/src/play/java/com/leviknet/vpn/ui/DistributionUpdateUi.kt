package com.leviknet.vpn.ui

import androidx.compose.runtime.Composable
import com.leviknet.vpn.core.update.AppUpdateDto
import com.leviknet.vpn.core.update.UpdateState

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DistributionUpdateSettingsItem(onCheckForUpdates: () -> Unit) = Unit

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DistributionUpdateDialog(
    updateState: UpdateState,
    onDownload: (AppUpdateDto) -> Unit,
    onDismiss: () -> Unit,
) = Unit
