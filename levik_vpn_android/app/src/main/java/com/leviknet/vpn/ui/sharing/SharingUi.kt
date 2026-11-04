package com.leviknet.vpn.ui.sharing

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import com.leviknet.vpn.R
import com.leviknet.vpn.core.sharing.SharingController
import com.leviknet.vpn.core.sharing.SharingInvitation
import com.leviknet.vpn.core.sharing.SharingNetworks
import com.leviknet.vpn.core.sharing.SharingSnapshot
import com.leviknet.vpn.core.sharing.SharingStateStore
import com.leviknet.vpn.ui.activationQrBitmap
import com.leviknet.vpn.ui.theme.LevikBlue
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live system tethering (hotspot/USB) state; false when the OS does not report it. */
@Composable
fun rememberTetheringActive(): Boolean {
    val context = LocalContext.current.applicationContext
    var active by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                active = SharingNetworks.isTethering(intent)
            }
        }
        // Sent by the tethering module, not by our app, so it must be exported. A spoofed
        // broadcast can only toggle the dismissible hint; sharing itself re-validates addresses.
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(SharingNetworks.TETHER_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        active = SharingNetworks.isTethering(sticky)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return active
}

/** Home banner: shown only while sharing runs or the hotspot is on and VPN is connected. */
@Composable
fun SharingHomeBanner(snapshot: SharingSnapshot, hotspotHint: Boolean, onOpen: () -> Unit, onDismissHint: () -> Unit) {
    val host = snapshot.host
    if (host == null && !hotspotHint) return
    Spacer(Modifier.height(12.dp))
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, LevikBlue.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = if (host == null) 0.dp else 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_hotspot), contentDescription = null, modifier = Modifier.size(18.dp), tint = LevikBlue)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when {
                            host == null -> R.string.sharing_hotspot_hint_title
                            host.ready -> R.string.sharing_active_title
                            else -> R.string.sharing_not_ready
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (host == null) stringResource(R.string.sharing_hotspot_hint_body)
                    else stringResource(R.string.sharing_until, time(host.expiresAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (host == null) {
                IconButton(onClick = onDismissHint) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.sharing_hint_dismiss),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Quick chip in the home feature row, next to routing/DoH/Anti-DPI. */
@Composable
fun SharingChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_hotspot),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.sharing_chip),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Permanent entry in Profile, styled like the Family card next to it. */
@Composable
fun SharingProfileEntry(snapshot: SharingSnapshot, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_hotspot), contentDescription = null, tint = LevikBlue, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sharing_title), fontWeight = FontWeight.SemiBold)
                Text(
                    when (val host = snapshot.host) {
                        null -> stringResource(R.string.sharing_profile_body)
                        else -> stringResource(if (host.ready) R.string.sharing_ready else R.string.sharing_not_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SharingGuestScreen(snapshot: SharingSnapshot, onManage: () -> Unit) {
    val context = LocalContext.current
    val guest = snapshot.guest ?: return
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text(stringResource(R.string.sharing_guest_active, guest.name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(if (guest.connected) R.string.sharing_guest_connected else R.string.sharing_guest_waiting))
        snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.sharing_until, time(guest.expiresAt)))
        Text(stringResource(R.string.sharing_guest_limit))
        OutlinedButton(onClick = onManage) { Text(stringResource(R.string.sharing_help)) }
        Button(onClick = { SharingController.disconnectGuest(context) }) { Text(stringResource(R.string.sharing_leave)) }
    }
}

/** Secrets live only in this dialog/state, never rememberSaveable or a URI log. */
@Composable
fun SharingDialog(
    snapshot: SharingSnapshot,
    ownerConnected: Boolean,
    incomingInvitation: String?,
    onReceive: (String) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var receive by remember { mutableStateOf(!ownerConnected || snapshot.guest != null) }
    var invitation by remember { mutableStateOf(incomingInvitation.orEmpty()) }
    var addresses by remember { mutableStateOf(SharingNetworks.localAddresses()) }
    var address by remember { mutableStateOf(addresses.singleOrNull().orEmpty()) }
    var trusted by remember { mutableStateOf(false) }
    var showProxy by remember { mutableStateOf(true) }
    var recipient by remember { mutableStateOf(ProxyRecipient.ANDROID) }
    var confirmInvite by remember { mutableStateOf<SharingInvitation?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var probeRunning by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<Boolean?>(null) }
    val invalid = stringResource(R.string.sharing_guest_invalid)
    val actionFailed = stringResource(R.string.sharing_action_failed)
    val noSettings = stringResource(R.string.sharing_open_settings_failed)
    LaunchedEffect(incomingInvitation) {
        if (incomingInvitation != null) { invitation = incomingInvitation; receive = true }
    }
    LaunchedEffect(snapshot.host?.address, snapshot.host?.proxyPort, snapshot.host?.ready) { probeResult = null }

    fun act(action: () -> Unit) {
        localError = null
        SharingStateStore.clearError()
        try { action() } catch (_: Exception) { localError = actionFailed }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 760.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sharing_title), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.sharing_close)) }
                }
                if (snapshot.guest == null && snapshot.host == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { receive = false }, enabled = !snapshot.busy) { Text(stringResource(R.string.sharing_host_short)) }
                        OutlinedButton(onClick = { receive = true }, enabled = !snapshot.busy) { Text(stringResource(R.string.sharing_receive_short)) }
                    }
                }
                (localError ?: snapshot.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (snapshot.busy) Text(stringResource(R.string.sharing_preparing))

                val guest = snapshot.guest
                val host = snapshot.host
                when {
                    guest != null -> {
                        Text(stringResource(R.string.sharing_guest_active, guest.name), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (guest.connected) R.string.sharing_guest_connected else R.string.sharing_guest_waiting))
                        Text(stringResource(R.string.sharing_until, time(guest.expiresAt)))
                        Text(stringResource(R.string.sharing_guest_limit), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { act { SharingController.disconnectGuest(context) } }) { Text(stringResource(R.string.sharing_leave)) }
                    }
                    host != null -> {
                        Text(stringResource(if (host.ready) R.string.sharing_ready else R.string.sharing_not_ready), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.sharing_until, time(host.expiresAt)))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showProxy = true }) { Text(stringResource(R.string.sharing_without_app)) }
                            OutlinedButton(onClick = { showProxy = false }) { Text(stringResource(R.string.sharing_with_app)) }
                        }
                        if (showProxy) {
                            CopyField(stringResource(R.string.sharing_address), host.address)
                            CopyField(stringResource(R.string.sharing_port), host.proxyPort.toString())
                            Text(stringResource(R.string.sharing_proxy_note), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.sharing_proxy_limit), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(enabled = host.ready && !probeRunning, onClick = {
                                val checked = host
                                probeRunning = true
                                scope.launch {
                                    try {
                                        val result = testProxy(checked.address, checked.proxyPort)
                                        if (SharingStateStore.state.value.host == checked) probeResult = result
                                    } finally { probeRunning = false }
                                }
                            }) { Text(stringResource(if (probeRunning) R.string.sharing_checking else R.string.sharing_check_proxy)) }
                            probeResult?.let { Text(stringResource(if (it) R.string.sharing_check_ok else R.string.sharing_check_failed)) }
                            Text(stringResource(R.string.sharing_choose_device), fontWeight = FontWeight.SemiBold)
                            ProxyRecipient.entries.forEach { choice ->
                                TextButton(onClick = { recipient = choice }, modifier = Modifier.fillMaxWidth()) {
                                    Text((if (recipient == choice) "✓ " else "") + stringResource(choice.title))
                                }
                            }
                            HorizontalDivider()
                            Text(stringResource(R.string.sharing_help), style = MaterialTheme.typography.titleMedium)
                            stringArrayResource(recipient.steps).forEachIndexed { index, step -> Text("${index + 1}. $step") }
                            Text(stringResource(R.string.sharing_guide_version), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.sharing_restore_title), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.sharing_restore_note))
                            Text(stringResource(recipient.restore))
                            Text(stringResource(R.string.sharing_troubleshooting), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(stringResource(R.string.sharing_invite_title), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.sharing_invite_note))
                            if (host.ready) {
                                val qr = remember(host.invitation) { activationQrBitmap(host.invitation, size = 768).asImageBitmap() }
                                Image(qr, contentDescription = stringResource(R.string.sharing_invite_qr),
                                    modifier = Modifier.align(Alignment.CenterHorizontally).size(248.dp).background(Color.White).padding(8.dp))
                                OutlinedButton(onClick = { copy(context, host.invitation, sensitive = true) }) { Text(stringResource(R.string.sharing_copy_invite)) }
                            }
                            Text(stringResource(R.string.sharing_invite_secret), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { confirmStop = true }) { Text(stringResource(R.string.sharing_stop)) }
                    }
                    receive -> {
                        Text(stringResource(R.string.sharing_receive), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.sharing_guest_steps))
                        OutlinedTextField(value = invitation, onValueChange = { if (it.length <= 2048) invitation = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sharing_invite_field)) },
                            visualTransformation = PasswordVisualTransformation(), maxLines = 3)
                        OutlinedButton(onClick = onScan, enabled = !snapshot.busy) { Text(stringResource(R.string.sharing_scan)) }
                        Button(enabled = invitation.isNotBlank() && !snapshot.busy, onClick = {
                            localError = null
                            confirmInvite = try { SharingInvitation.parse(invitation) } catch (_: Exception) { localError = invalid; null }
                        }) { Text(stringResource(R.string.sharing_join)) }
                        Text(stringResource(R.string.sharing_guest_limit), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text(stringResource(R.string.sharing_host_steps))
                        if (!ownerConnected) Text(stringResource(R.string.sharing_host_requires_vpn), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = {
                            val opened = runCatching { context.startActivity(Intent("android.settings.TETHER_SETTINGS")); true }.getOrDefault(false)
                            if (!opened && runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)); true }.getOrDefault(false).not()) localError = noSettings
                        }) { Text(stringResource(R.string.sharing_hotspot_settings)) }
                        OutlinedButton(onClick = {
                            addresses = SharingNetworks.localAddresses()
                            if (address !in addresses) address = addresses.singleOrNull().orEmpty()
                            trusted = false
                        }) { Text(stringResource(R.string.sharing_refresh)) }
                        if (addresses.isEmpty()) Text(stringResource(R.string.sharing_network_empty)) else {
                            Text(stringResource(R.string.sharing_network_label), fontWeight = FontWeight.SemiBold)
                            addresses.forEach { candidate ->
                                OutlinedButton(onClick = { address = candidate; trusted = false }, modifier = Modifier.fillMaxWidth()) {
                                    Text((if (candidate == address) "✓ " else "") + candidate)
                                }
                            }
                            Text(stringResource(R.string.sharing_network_note), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = trusted, onCheckedChange = { trusted = it })
                            Text(stringResource(R.string.sharing_trusted), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.sharing_restart_notice), style = MaterialTheme.typography.bodySmall)
                        Button(enabled = ownerConnected && trusted && address in addresses && !snapshot.busy,
                            onClick = { act { SharingController.startHost(context, address) } }) { Text(stringResource(R.string.sharing_start)) }
                    }
                }
            }
        }
    }
    confirmInvite?.let { parsed ->
        AlertDialog(onDismissRequest = { confirmInvite = null }, title = { Text(stringResource(R.string.sharing_confirm_join)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(parsed.name)
                Text(stringResource(R.string.sharing_until, time(parsed.expiresAt)))
                Text(stringResource(R.string.sharing_guest_replace))
            } },
            confirmButton = { Button(onClick = { confirmInvite = null; onReceive(invitation.trim()); invitation = "" }) { Text(stringResource(R.string.sharing_join)) } },
            dismissButton = { TextButton(onClick = { confirmInvite = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.sharing_stop_confirm)) }, text = { Text(stringResource(R.string.sharing_stop_body)) },
        confirmButton = { Button(onClick = { confirmStop = false; act { SharingController.stopHost(context) } }) { Text(stringResource(R.string.sharing_stop)) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CopyField(label: String, value: String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text(label, style = MaterialTheme.typography.labelMedium); SelectionContainer { Text(value, style = MaterialTheme.typography.titleMedium) } }
        TextButton(onClick = { copy(context, value, sensitive = false) }) { Text(stringResource(R.string.sharing_copy)) }
    }
}

private fun copy(context: Context, value: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Levik", value)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

private fun time(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

/** Tests the actual configured HTTP CONNECT path, not the app's ordinary network. */
private suspend fun testProxy(address: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    val connection = URL("https://www.gstatic.com/generate_204").openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(address, port))) as HttpURLConnection
    try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.responseCode == 204
    } catch (_: Exception) { false } finally { connection.disconnect() }
}
