package com.leviknet.vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.leviknet.vpn.R

/**
 * Explains connection quality statistics once. Direct builds collect them by
 * default after this notice; Play builds ask for an affirmative choice.
 */
@Composable
internal fun ConnectionTelemetryNoticeDialog(
    visible: Boolean,
    requiresConsent: Boolean,
    onOpenDetails: () -> Unit,
    onAnswer: (allow: Boolean) -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(painterResource(R.drawable.ic_privacy), contentDescription = null) },
        title = { Text(stringResource(R.string.connection_telemetry_notice_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.connection_telemetry_notice_body))
                TextButton(onClick = onOpenDetails) {
                    Text(stringResource(R.string.connection_telemetry_details))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAnswer(true) }) {
                Text(
                    stringResource(
                        if (requiresConsent) R.string.connection_telemetry_allow else R.string.connection_telemetry_accept,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Icon(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.connection_telemetry_decline))
            }
        },
    )
}
