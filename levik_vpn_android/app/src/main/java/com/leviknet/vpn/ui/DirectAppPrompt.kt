package com.leviknet.vpn.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.leviknet.vpn.R
import com.leviknet.vpn.core.platform.DirectAppSuggestion

/**
 * "Sber may not work with a VPN. Let it bypass the VPN?" for a bank from the
 * signed app catalog, docs/app-platform.md "App catalog". Either answer is
 * remembered; dismissing asks again next time.
 */
@Composable
internal fun DirectAppDialog(
    suggestion: DirectAppSuggestion,
    onRouteDirectly: () -> Unit,
    onKeepInVpn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    val name = suggestion.app.name(russian)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.direct_app_title, name)) },
        text = { Text(stringResource(R.string.direct_app_body)) },
        confirmButton = {
            TextButton(onClick = onRouteDirectly) { Text(stringResource(R.string.direct_app_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepInVpn) { Text(stringResource(R.string.direct_app_keep)) }
        },
    )
}
