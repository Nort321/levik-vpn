package com.leviknet.vpn.ui.drop

import android.app.DownloadManager
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leviknet.vpn.R
import com.leviknet.vpn.core.drop.AndroidDropFolders
import com.leviknet.vpn.core.drop.DropAction
import com.leviknet.vpn.core.drop.DropAnswer
import com.leviknet.vpn.core.drop.DropFormat
import com.leviknet.vpn.core.drop.DropPrompt
import com.leviknet.vpn.core.drop.DropState
import com.leviknet.vpn.core.drop.DropTransferView
import com.leviknet.vpn.core.drop.LinkStatus
import com.leviknet.vpn.core.drop.SessionPath
import com.leviknet.vpn.core.drop.Trust
import com.leviknet.vpn.ui.theme.LevikBlue
import com.leviknet.vpn.ui.theme.LevikGreen
import kotlinx.coroutines.delay

private const val DOCK_FILES = 4
private const val DONE_HIDE_MS = 6_000L
private const val ENDED_HIDE_MS = 4_000L

private enum class DockPhase { SENDING, OFFER, CONFIRM, CODE, WAITING, ACTIVE, PAUSED, DONE, ENDED, FAILED }

private fun phaseOf(transfer: DropTransferView): DockPhase {
    transfer.prompt?.let { return if (it.kind == DropPrompt.Kind.OFFER) DockPhase.OFFER else DockPhase.CONFIRM }
    return when (transfer.state) {
        DropState.DONE -> DockPhase.DONE
        DropState.FAILED, DropState.EXPIRED -> DockPhase.FAILED
        DropState.DECLINED -> if (transfer.sender) DockPhase.FAILED else DockPhase.ENDED
        DropState.CANCELLED -> DockPhase.ENDED
        DropState.PAUSED -> DockPhase.PAUSED
        DropState.ACTIVE -> if (transfer.link == LinkStatus.SESSION) DockPhase.ACTIVE else DockPhase.WAITING
        DropState.OFFERED -> if (transfer.verificationCode != null) DockPhase.CODE else DockPhase.WAITING
    }
}

/**
 * LevikDrop above the tab bar on every screen, docs/levik-drop.md: the offer
 * and the code to compare, then progress and the result of the same transfer.
 * It slides in for one transfer at a time and folds into a small pill.
 */
@Composable
internal fun DropDock(state: DropUiState, controller: DropController, modifier: Modifier = Modifier) {
    val transfers = state.snapshot.transfers
    // Only transfers this screen saw open get a result card, not old history.
    val seenOpen = remember { HashSet<String>() }
    transfers.forEach { if (it.state.open) seenOpen.add(it.id) }
    val dismissed = remember { mutableStateListOf<String>() }
    var collapsedId by rememberSaveable { mutableStateOf<String?>(null) }

    val featured = transfers.firstOrNull { it.prompt != null }
        ?: transfers.firstOrNull { it.state.open }
        ?: transfers.firstOrNull { !it.state.open && it.id in seenOpen && it.id !in dismissed }
    val sendingName = state.sendingTo?.takeIf { featured == null || !featured.state.open }
        ?.let { id -> state.snapshot.devices.firstOrNull { it.deviceId == id }?.name.orEmpty() }

    val phase = featured?.let(::phaseOf)
    LaunchedEffect(featured?.id, phase) {
        val id = featured?.id ?: return@LaunchedEffect
        val wait = when (phase) {
            DockPhase.DONE -> DONE_HIDE_MS
            DockPhase.ENDED -> ENDED_HIDE_MS
            else -> return@LaunchedEffect
        }
        delay(wait)
        dismissed.add(id)
    }

    val prompt = featured?.prompt
    val collapsed = when {
        featured == null -> false
        prompt != null -> controller.promptKey(featured.id, prompt.code) in state.later
        else -> collapsedId == featured.id
    }
    val content = when {
        featured != null -> DockContent.Transfer(featured, collapsed)
        sendingName != null -> DockContent.Sending(sendingName)
        else -> null
    }
    // Keeps the last card on screen while it slides away.
    val last = remember { LastContent() }
    if (content != null) last.value = content

    AnimatedVisibility(
        visible = content != null,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(220)),
    ) {
        AnimatedContent(
            targetState = content ?: last.value,
            contentKey = { (it as? DockContent.Transfer)?.let { item -> "${item.transfer.id}:${item.collapsed}" } ?: "sending" },
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false) },
            label = "drop-dock",
        ) { item ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                when (item) {
                    is DockContent.Sending -> DockCard {
                        DockHead(DockPhase.SENDING, R.drawable.ic_upload, stringResource(R.string.drop_to, item.deviceName), stringResource(R.string.drop_dock_preparing), null)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    }
                    is DockContent.Transfer -> if (item.collapsed) {
                        DockPill(item.transfer) {
                            item.transfer.prompt?.let { controller.reopen(item.transfer.id, it.code) }
                            collapsedId = null
                        }
                    } else {
                        DockTransferCard(
                            transfer = item.transfer,
                            answering = state.answering,
                            controller = controller,
                            onCollapse = {
                                val itemPrompt = item.transfer.prompt
                                if (itemPrompt != null) controller.later(item.transfer.id, itemPrompt.code) else collapsedId = item.transfer.id
                            },
                            onHide = { dismissed.add(item.transfer.id) },
                        )
                    }
                    null -> Unit
                }
            }
        }
    }
}

private class LastContent(var value: DockContent? = null)

private sealed class DockContent {
    data class Sending(val deviceName: String) : DockContent()
    data class Transfer(val transfer: DropTransferView, val collapsed: Boolean) : DockContent()
}

@Composable
private fun DockCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun DockTransferCard(
    transfer: DropTransferView,
    answering: Boolean,
    controller: DropController,
    onCollapse: () -> Unit,
    onHide: () -> Unit,
) {
    val context = LocalContext.current
    val phase = phaseOf(transfer)
    val final = phase == DockPhase.DONE || phase == DockPhase.ENDED || phase == DockPhase.FAILED
    val title = when (phase) {
        DockPhase.OFFER -> stringResource(R.string.drop_offer_title, transfer.peerName)
        DockPhase.CONFIRM -> stringResource(R.string.drop_confirm_title)
        else -> stringResource(if (transfer.sender) R.string.drop_to else R.string.drop_from, transfer.peerName)
    }
    val subtitle = when (phase) {
        DockPhase.CONFIRM -> stringResource(if (transfer.sender) R.string.drop_confirm_sending else R.string.drop_confirm_receiving, transfer.peerName)
        else -> filesLine(transfer)
    }
    val icon = when (phase) {
        DockPhase.DONE -> R.drawable.ic_check
        DockPhase.FAILED -> R.drawable.ic_close
        else -> if (transfer.sender) R.drawable.ic_upload else R.drawable.ic_download
    }
    DockCard {
        DockHead(
            phase = phase,
            icon = icon,
            title = title,
            subtitle = subtitle,
            action = if (final) DockHeadAction(R.drawable.ic_close, stringResource(R.string.drop_dock_hide), onHide)
            else DockHeadAction(R.drawable.ic_chevron_down, stringResource(R.string.drop_dock_collapse), onCollapse),
        )
        AnimatedContent(
            targetState = phase,
            transitionSpec = { (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 6 }) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
            label = "drop-dock-phase",
        ) { current ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val prompt = transfer.prompt
                if (prompt?.trust == Trust.CHANGED) {
                    Text(stringResource(R.string.drop_key_changed, transfer.peerName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                when (current) {
                    DockPhase.OFFER -> {
                        DockFiles(transfer)
                        Text(
                            "${stringResource(R.string.drop_files_size, DropFormat.fileCount(context, transfer.fileCount), DropFormat.bytes(context, transfer.totalBytes))} · " +
                                stringResource(R.string.drop_offer_folder, folderLabel(transfer.folder)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        prompt?.code?.let { code -> DockCode(code, stringResource(R.string.drop_code_hint, transfer.peerName)) }
                        DockNote(stringResource(R.string.drop_offer_safety))
                        DockActions {
                            TextButton(onClick = { controller.answer(transfer.id, DropAnswer.DECLINE, prompt?.code) }, enabled = !answering) {
                                Text(stringResource(R.string.drop_decline), color = MaterialTheme.colorScheme.error)
                            }
                            Button(onClick = { controller.answer(transfer.id, DropAnswer.ACCEPT, prompt?.code) }, enabled = !answering, shape = RoundedCornerShape(12.dp)) {
                                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.drop_accept))
                            }
                        }
                    }
                    DockPhase.CONFIRM -> {
                        DockCode(prompt?.code.orEmpty(), stringResource(R.string.drop_code_hint, transfer.peerName))
                        DockActions {
                            TextButton(onClick = { controller.answer(transfer.id, DropAnswer.REJECT, prompt?.code) }, enabled = !answering) {
                                Text(stringResource(R.string.drop_codes_differ), color = MaterialTheme.colorScheme.error)
                            }
                            Button(onClick = { controller.answer(transfer.id, DropAnswer.CONFIRM, prompt?.code) }, enabled = !answering, shape = RoundedCornerShape(12.dp)) {
                                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.drop_codes_match))
                            }
                        }
                    }
                    DockPhase.CODE -> {
                        DockCode(transfer.verificationCode.orEmpty(), stringResource(R.string.drop_code_available_hint))
                        DockStatus(transfer)
                        DockActions { CancelButton(transfer, controller) }
                    }
                    DockPhase.WAITING, DockPhase.SENDING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                        DockStatus(transfer)
                        DockActions { CancelButton(transfer, controller) }
                    }
                    DockPhase.ACTIVE, DockPhase.PAUSED -> {
                        DockProgress(transfer, moving = current == DockPhase.ACTIVE)
                        if (current == DockPhase.PAUSED) DockStatus(transfer)
                        DockActions {
                            if (current == DockPhase.ACTIVE) {
                                TextButton(onClick = { controller.control(transfer.id, DropAction.PAUSE) }) { Text(stringResource(R.string.drop_pause)) }
                            } else if (transfer.link == null) {
                                TextButton(onClick = { controller.control(transfer.id, DropAction.RESUME) }) { Text(stringResource(R.string.drop_resume)) }
                            }
                            CancelButton(transfer, controller)
                        }
                    }
                    DockPhase.DONE -> {
                        DockProgress(transfer, moving = false)
                        Text(
                            "${dropStatus(transfer)}: ${stringResource(R.string.drop_files_size, DropFormat.fileCount(context, transfer.fileCount), DropFormat.bytes(context, transfer.totalBytes))}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = LevikGreen,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (!transfer.sender && transfer.saved && transfer.folder == AndroidDropFolders.DOWNLOADS) {
                            DockActions {
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text(stringResource(R.string.drop_show)) }
                            }
                        }
                    }
                    DockPhase.ENDED, DockPhase.FAILED -> DockStatus(transfer)
                }
            }
        }
    }
}

private data class DockHeadAction(val icon: Int, val label: String, val onClick: () -> Unit)

@Composable
private fun DockHead(phase: DockPhase, icon: Int, title: String, subtitle: String, action: DockHeadAction?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DockBadge(phase, icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) {
            IconButton(onClick = action.onClick) {
                Icon(painterResource(action.icon), contentDescription = action.label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The icon breathes while waiting for someone and pops when the files arrive. */
@Composable
private fun DockBadge(phase: DockPhase, icon: Int) {
    val tint = when (phase) {
        DockPhase.DONE -> LevikGreen
        DockPhase.FAILED -> MaterialTheme.colorScheme.error
        DockPhase.PAUSED, DockPhase.ENDED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> LevikBlue
    }
    val pulsing = phase in setOf(DockPhase.SENDING, DockPhase.OFFER, DockPhase.CONFIRM, DockPhase.CODE, DockPhase.WAITING)
    val transition = rememberInfiniteTransition(label = "drop-badge")
    val ring by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing)), label = "drop-badge-ring")
    val bob by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drop-badge-bob")
    val pop = remember(phase) { Animatable(if (phase == DockPhase.DONE) 0.6f else 1f) }
    LaunchedEffect(phase) {
        if (phase == DockPhase.DONE) pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
    }
    Box(
        Modifier
            .size(42.dp)
            .scale(pop.value)
            .drawBehind {
                if (pulsing) {
                    val grow = 6.dp.toPx() * ring
                    drawRoundRect(
                        color = tint.copy(alpha = 0.5f * (1f - ring)),
                        topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow),
                        size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(14.dp.toPx() + grow),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp).graphicsLayer { if (phase == DockPhase.ACTIVE) translationY = -2.dp.toPx() * bob },
        )
    }
}

@Composable
private fun DockFiles(transfer: DropTransferView) {
    val context = LocalContext.current
    val files = transfer.files.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.take(DOCK_FILES).forEachIndexed { index, file ->
            // Rows slide in one after another.
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(index * 50L)
                appear.animateTo(1f, tween(280))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = appear.value
                        translationX = (1f - appear.value) * 12.dp.toPx()
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(7.dp)).background(LevikBlue.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(extension(file.name), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = LevikBlue)
                }
                Spacer(Modifier.width(10.dp))
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(DropFormat.bytes(context, file.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (files.size > DOCK_FILES) {
            Text(stringResource(R.string.drop_offer_more, files.size - DOCK_FILES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Digits rise one by one, so the eye reads the code group by group. */
@Composable
private fun DockCode(code: String, hint: String) {
    DockNote(hint)
    val description = stringResource(R.string.drop_code_description, code.replace(" ", ""))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
    ) {
        code.forEachIndexed { index, character ->
            if (character == ' ') {
                Spacer(Modifier.width(10.dp))
                return@forEachIndexed
            }
            val appear = remember(code) { Animatable(0f) }
            LaunchedEffect(code) {
                delay(index * 45L)
                appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
            }
            Box(
                Modifier
                    .padding(horizontal = 2.5.dp)
                    .graphicsLayer {
                        alpha = appear.value.coerceIn(0f, 1f)
                        translationY = (1f - appear.value) * 10.dp.toPx()
                    }
                    .size(width = 34.dp, height = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(character.toString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
        }
    }
}

/** A smooth bar with a light running over it while bytes move. */
@Composable
private fun DockProgress(transfer: DropTransferView, moving: Boolean) {
    val context = LocalContext.current
    val target = if (transfer.totalBytes > 0) (transfer.doneBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f) else if (transfer.state == DropState.DONE) 1f else 0f
    val progress by animateFloatAsState(target, tween(400), label = "drop-progress")
    val color = when (transfer.state) {
        DropState.DONE -> LevikGreen
        DropState.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> LevikBlue
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val shimmer = rememberInfiniteTransition(label = "drop-shimmer")
    val sweep by shimmer.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "drop-sweep")
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .drawBehind {
                drawRect(track)
                val width = size.width * progress
                drawRoundRect(color, size = androidx.compose.ui.geometry.Size(width, size.height), cornerRadius = CornerRadius(size.height / 2))
                if (moving && width > 0f) {
                    val x = size.width * sweep
                    val glow = size.width * 0.18f
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                            startX = x - glow,
                            endX = x + glow,
                        ),
                        size = androidx.compose.ui.geometry.Size(width, size.height),
                    )
                }
            }
            .semantics { contentDescription = "${(target * 100).toInt()}%" },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        val done = DropFormat.bytes(context, transfer.doneBytes)
        val total = DropFormat.bytes(context, transfer.totalBytes)
        Text(
            transfer.bytesPerSecond?.takeIf { moving }?.let { stringResource(R.string.drop_progress_rate, done, total, DropFormat.bytes(context, it)) }
                ?: stringResource(R.string.drop_progress_line, done, total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (moving) {
            Text(
                stringResource(if (transfer.path == SessionPath.LAN) R.string.drop_dock_lan else R.string.drop_dock_relay),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = LevikBlue,
                modifier = Modifier.clip(CircleShape).background(LevikBlue.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** The folded card: a ring with the progress, or a blue dot that calls for an answer. */
@Composable
private fun DockPill(transfer: DropTransferView, onExpand: () -> Unit) {
    val waiting = transfer.prompt != null
    val target = if (transfer.totalBytes > 0) (transfer.doneBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(target, tween(400), label = "drop-pill-progress")
    val text = when {
        waiting -> stringResource(R.string.drop_dock_waiting_answer, transfer.peerName)
        transfer.state == DropState.DONE -> dropStatus(transfer)
        else -> "${stringResource(if (transfer.sender) R.string.drop_dock_sending else R.string.drop_dock_receiving)} · ${(progress * 100).toInt()}%"
    }
    val label = stringResource(R.string.drop_dock_expand)
    val ringColor = if (transfer.state == DropState.DONE) LevikGreen else LevikBlue
    val track = MaterialTheme.colorScheme.surfaceVariant
    val pulse = rememberInfiniteTransition(label = "drop-pill")
    val glow by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800)), label = "drop-pill-glow")
    Surface(
        onClick = onExpand,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 8.dp,
        modifier = Modifier.semantics { contentDescription = "$label: $text" },
    ) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).drawBehind {
                    val stroke = 3.dp.toPx()
                    if (waiting) {
                        drawCircle(ringColor.copy(alpha = 0.45f * (1f - glow)), radius = size.minDimension / 2 + 6.dp.toPx() * glow)
                        drawCircle(ringColor)
                    } else {
                        val inset = stroke / 2
                        drawCircle(track, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                        drawArc(
                            ringColor,
                            startAngle = -90f,
                            sweepAngle = if (transfer.state == DropState.DONE) 360f else 360f * progress,
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (transfer.state == DropState.DONE) R.drawable.ic_check else if (transfer.sender) R.drawable.ic_upload else R.drawable.ic_download),
                    contentDescription = null,
                    tint = if (waiting) Color.White else ringColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DockStatus(transfer: DropTransferView) {
    Text(
        dropStatus(transfer),
        style = MaterialTheme.typography.bodySmall,
        color = if (transfer.state == DropState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun DockNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DockActions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
private fun CancelButton(transfer: DropTransferView, controller: DropController) {
    TextButton(onClick = { controller.control(transfer.id, DropAction.CANCEL) }) {
        Text(stringResource(R.string.drop_cancel), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun filesLine(transfer: DropTransferView): String {
    val context = LocalContext.current
    val files = transfer.files
    return if (!files.isNullOrEmpty() && files.size <= 3) files.joinToString(", ") { it.name }
    else stringResource(R.string.drop_files_size, DropFormat.fileCount(context, transfer.fileCount), DropFormat.bytes(context, transfer.totalBytes))
}

/** "PDF" for report.pdf; a dot for names without one. */
private fun extension(name: String): String {
    val ext = name.substringAfterLast('.', "").takeIf { name.lastIndexOf('.') > 0 }.orEmpty()
    return if (ext.isNotEmpty() && ext.length <= 4) ext.uppercase() else "•"
}
