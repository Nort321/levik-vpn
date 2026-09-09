package com.leviknet.vpn.ui

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
import com.leviknet.vpn.R

@Composable
internal fun DistributionDataDisclosureDialog(
    disclosure: OptionalDataDisclosure?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    if (disclosure == null) return
    val body = when (disclosure) {
        OptionalDataDisclosure.MAP -> R.string.play_map_disclosure
        OptionalDataDisclosure.DIAGNOSTICS -> R.string.play_diagnostics_disclosure
        OptionalDataDisclosure.WIFI -> R.string.play_wifi_disclosure
    }
    val title = when (disclosure) {
        OptionalDataDisclosure.MAP -> R.string.whitelist_map_title
        OptionalDataDisclosure.DIAGNOSTICS -> R.string.censorship_radar_title
        OptionalDataDisclosure.WIFI -> R.string.play_wifi_disclosure_title
    }
    PlayDataDisclosureDialog(title, body, onAccept, onDecline)
}

@Composable
internal fun DistributionInstalledAppsDisclosure(
    visible: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    if (visible) {
        PlayDataDisclosureDialog(
            R.string.split_tunneling_title,
            R.string.play_apps_disclosure,
            onAccept,
            onDecline,
        )
    }
}

@Composable
private fun PlayDataDisclosureDialog(
    title: Int,
    body: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(title)) },
        text = {
            Text(
                stringResource(body),
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Icon(painterResource(R.drawable.ic_privacy), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_data_disclosure_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Icon(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_data_disclosure_decline))
            }
        },
    )
}
