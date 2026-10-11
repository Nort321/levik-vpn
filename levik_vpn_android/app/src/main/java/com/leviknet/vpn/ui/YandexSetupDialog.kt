package com.leviknet.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.leviknet.vpn.R
import com.leviknet.vpn.vpn.YandexContract

@Composable
internal fun YandexSetupDialog(initialUrl: String, telemost: Boolean = false, onSaveTelemost: (String) -> Unit = {}, onDismiss: () -> Unit, onOpenGuest: (String) -> Unit) {
    var documentUrl by remember { mutableStateOf(initialUrl) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.yandex_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(if (telemost) R.string.telemost_guide else R.string.yandex_guide))
                OutlinedTextField(
                    value = documentUrl,
                    onValueChange = { if (it.length <= 256) { documentUrl = it; invalid = false } },
                    label = { Text(stringResource(if (telemost) R.string.telemost_link_label else R.string.yandex_document_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    isError = invalid,
                    supportingText = { Text(stringResource(if (telemost) R.string.telemost_link_hint else R.string.yandex_document_hint)) },
                )
                Text(stringResource(if (telemost) R.string.telemost_privacy else R.string.yandex_privacy), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val valid = runCatching { if (telemost) YandexContract.validateConferenceUrl(documentUrl.trim()) else YandexContract.validateDocumentUrl(documentUrl.trim()) }.getOrNull()
                if (valid == null) invalid = true else if (telemost) onSaveTelemost(valid) else onOpenGuest(valid)
            }) { Text(stringResource(if (telemost) R.string.telemost_save else R.string.yandex_open_guest)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
