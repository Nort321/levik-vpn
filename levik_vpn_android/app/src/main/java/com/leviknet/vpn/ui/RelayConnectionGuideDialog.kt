package com.leviknet.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R

@Composable
internal fun RelayConnectionGuideDialog(
    onDismiss: () -> Unit,
    onContinue: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(painterResource(R.drawable.ic_shield_check), contentDescription = null)
        },
        title = { Text(stringResource(R.string.relay_guide_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.relay_guide_intro))
                listOf(
                    R.string.relay_guide_step_network,
                    R.string.relay_guide_step_login,
                    R.string.relay_guide_step_return,
                ).forEachIndexed { index, text ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "${index + 1}.",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(text))
                    }
                }
                Text(
                    stringResource(R.string.relay_guide_alternative),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onContinue ?: onDismiss) {
                Icon(
                    painterResource(if (onContinue != null) R.drawable.ic_shield_check else R.drawable.ic_close),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (onContinue != null) R.string.relay_guide_continue else R.string.relay_guide_done))
            }
        },
        dismissButton = if (onContinue != null) {
            {
                TextButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cancel))
                }
            }
        } else null,
    )
}
