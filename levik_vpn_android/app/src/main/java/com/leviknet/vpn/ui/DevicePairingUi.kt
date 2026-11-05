package com.leviknet.vpn.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leviknet.vpn.R
import com.leviknet.vpn.ui.theme.LevikBlue
import kotlinx.coroutines.delay

/*
 * Signing a device in from another one, both ways, docs/app-platform.md "Device pairing".
 * Signed out: show this device's QR (a signed-in device scans it), scan the QR of
 * a signed-in device, or type the code under it. Signed in: show a QR for a new
 * device, scan the new device's QR, or type the code it shows.
 */

/** TVs and some boxes have no camera: scanning is not offered there. */
internal fun Context.hasCamera(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** One way to pair, as a full-width button with its explanation. */
@Composable
private fun PairingOption(iconRes: Int?, title: String, description: String, primary: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                if (iconRes != null) {
                    Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.8f))
        }
    }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    val shape = RoundedCornerShape(14.dp)
    if (primary) Button(onClick = onClick, modifier = modifier, shape = shape) { content() }
    else OutlinedButton(onClick = onClick, modifier = modifier, shape = shape) { content() }
}

/** «Вход по QR-коду» on the sign-in screen: which device scans which. */
@Composable
internal fun QrLoginChoiceDialog(
    hasCamera: Boolean,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.login_qr), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.pairing_login_choice_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Without a camera, showing the QR is the way in; it goes first.
                PairingOption(R.drawable.ic_qr_code, stringResource(R.string.pairing_show_qr),
                    stringResource(R.string.pairing_login_show_description), primary = !hasCamera, onClick = onShowQr)
                if (hasCamera) {
                    PairingOption(R.drawable.ic_login, stringResource(R.string.pairing_scan_qr),
                        stringResource(R.string.pairing_login_scan_description), primary = true, onClick = onScan)
                }
                PairingOption(null, stringResource(R.string.pairing_enter_code),
                    stringResource(R.string.pairing_login_code_description), primary = false, onClick = onEnterCode)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** «Подключить устройство» in the profile: show a QR for the new device, or take its code. */
@Composable
internal fun ConnectDeviceDialog(
    hasCamera: Boolean,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_connect_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.pairing_connect_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                PairingOption(R.drawable.ic_qr_code, stringResource(R.string.pairing_show_qr),
                    stringResource(R.string.pairing_connect_show_description), primary = true, onClick = onShowQr)
                if (hasCamera) {
                    PairingOption(R.drawable.ic_login, stringResource(R.string.pairing_scan_qr),
                        stringResource(R.string.pairing_connect_scan_description), primary = false, onClick = onScan)
                }
                PairingOption(null, stringResource(R.string.pairing_enter_code),
                    stringResource(R.string.pairing_connect_code_description), primary = false, onClick = onEnterCode)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A code typed with the remote or the keyboard. [parse] returns the canonical
 * code or null; [groups] of four characters are shown as typed.
 */
@Composable
internal fun PairingCodeDialog(
    title: String,
    description: String,
    groups: Int,
    parse: (String) -> String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val placeholder = List(groups) { "XXXX" }.joinToString("-")
    val submit = {
        val code = parse(value)
        if (code == null) invalid = true else onSubmit(code)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        invalid = false
                        val characters = input.uppercase().filter { it.isLetterOrDigit() }.take(groups * 4)
                        value = characters.chunked(4).joinToString("-")
                    },
                    label = { Text(stringResource(R.string.pairing_code_label)) },
                    placeholder = { Text(placeholder, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.pairing_code_invalid, placeholder)) }
                    } else {
                        null
                    },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { Button(onClick = submit) { Text(stringResource(R.string.pairing_code_submit)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The QR a signed-in device shows for a new one, with its code and what happens next. */
@Composable
internal fun DevicePairingDialog(
    state: DevicePairingUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status, state.expiresAtMillis) {
        while (state.status == DevicePairingStatus.PENDING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_connect_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val pairing = state.pairing
                when {
                    state.status == DevicePairingStatus.CREATING -> CircularProgressIndicator()
                    state.status == DevicePairingStatus.FAILED || pairing == null ->
                        Text(stringResource(R.string.pairing_failed), textAlign = TextAlign.Center)
                    state.status == DevicePairingStatus.CONNECTED -> {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = LevikBlue, modifier = Modifier.size(40.dp))
                        Text(
                            stringResource(R.string.pairing_connected, state.deviceName ?: stringResource(R.string.pairing_device_fallback)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(stringResource(R.string.pairing_connected_description), textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        val expired = state.status == DevicePairingStatus.EXPIRED
                        val qr = remember(pairing.uri) { activationQrBitmap(pairing.uri).asImageBitmap() }
                        Image(
                            bitmap = qr,
                            contentDescription = stringResource(R.string.pairing_qr_description),
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .alpha(if (expired) 0.25f else 1f),
                        )
                        Text(stringResource(R.string.pairing_or_enter_code), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val spoken = pairing.code.toList().joinToString(" ")
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                text = pairing.code,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 3.sp,
                                color = LevikBlue,
                                modifier = Modifier
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                                    .semantics { contentDescription = spoken },
                            )
                        }
                        Text(stringResource(R.string.pairing_show_steps), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        val seconds = ((state.expiresAtMillis - now) / 1_000).coerceAtLeast(0)
                        Text(
                            if (expired) stringResource(R.string.pairing_expired)
                            else stringResource(R.string.pairing_waiting, "%d:%02d".format(seconds / 60, seconds % 60)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(R.string.pairing_warning), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {
            when (state.status) {
                DevicePairingStatus.CONNECTED -> Button(onClick = onRefresh) { Text(stringResource(R.string.pairing_connect_another)) }
                DevicePairingStatus.EXPIRED, DevicePairingStatus.FAILED ->
                    Button(onClick = onRefresh) { Text(stringResource(R.string.pairing_new_code)) }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (state.status == DevicePairingStatus.CONNECTED) R.string.pairing_done else R.string.cancel))
            }
        },
    )
}
