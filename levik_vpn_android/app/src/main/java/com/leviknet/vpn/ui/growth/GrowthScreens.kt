package com.leviknet.vpn.ui.growth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.leviknet.vpn.R
import com.leviknet.vpn.core.network.FamilyMember
import com.leviknet.vpn.core.network.GiftLink
import com.leviknet.vpn.core.network.InvitePreview
import com.leviknet.vpn.ui.activationQrBitmap
import com.leviknet.vpn.ui.theme.LevikBlue
import com.leviknet.vpn.ui.theme.LevikDimensions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Asks before anything is accepted: the link alone never changes the account. */
@Composable
internal fun InviteDialog(
    state: InviteUiState,
    onAccept: () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = state.preview
    val claimed = state.claimed
    AlertDialog(
        onDismissRequest = { if (!state.claiming) onDismiss() },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_crown),
                contentDescription = null,
                tint = LevikBlue,
            )
        },
        title = {
            Text(
                when {
                    claimed != null -> stringResource(claimedTitle(claimed.kind))
                    preview != null -> stringResource(inviteTitle(preview.kind))
                    else -> stringResource(R.string.invite_title_generic)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                    claimed != null -> Text(stringResource(claimedBody(claimed.kind)))
                    preview != null -> InvitePreviewText(preview)
                }
                state.error?.let { code ->
                    Text(
                        stringResource(growthErrorText(code)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when {
                claimed != null && claimed.kind != "referral" -> Button(onClick = onOpenSubscriptions) {
                    Text(stringResource(R.string.invite_open_subscriptions))
                }
                claimed != null -> Button(onClick = onDismiss) { Text(stringResource(R.string.invite_done)) }
                state.error == GrowthController.ACCOUNT_REQUIRED -> Button(onClick = onSignIn) {
                    Text(stringResource(R.string.invite_sign_in))
                }
                preview?.status == "active" -> Button(onClick = onAccept, enabled = !state.claiming) {
                    if (state.claiming) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(acceptLabel(preview.kind)))
                    }
                }
                state.error != null && preview == null -> Button(onClick = onRetry) {
                    Text(stringResource(R.string.invite_retry))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (claimed == null) {
                TextButton(onClick = onDismiss, enabled = !state.claiming) {
                    Text(stringResource(R.string.invite_not_now))
                }
            }
        },
    )
}

@Composable
private fun InvitePreviewText(preview: InvitePreview) {
    val inviter = preview.inviterName ?: stringResource(R.string.invite_someone)
    when (preview.status) {
        "used" -> Text(stringResource(R.string.invite_status_used))
        "expired" -> Text(stringResource(R.string.invite_status_expired))
        else -> when (preview.kind) {
            "family" -> {
                Text(stringResource(R.string.invite_family_body, inviter))
                val seats = preview.seats ?: 0
                val free = (seats - (preview.usedSeats ?: 0)).coerceAtLeast(0)
                Text(
                    stringResource(R.string.invite_family_seats, free, seats),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            "gift" -> Text(
                stringResource(
                    R.string.invite_gift_body,
                    inviter,
                    preview.tariffTitle ?: "Levik VPN",
                    preview.months ?: 1,
                ),
            )
            else -> Text(stringResource(R.string.invite_referral_body, inviter, preview.discountPercent ?: 0))
        }
    }
}

/** The profile entry to family, the invite link and gifts. */
@Composable
internal fun FamilyEntryCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_profile),
                contentDescription = null,
                tint = LevikBlue,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.family_entry_title), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.family_entry_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Family, the personal invite link and gifts, opened from the profile. */
@Composable
internal fun FamilyAndFriendsScreen(
    state: FamilyUiState,
    externalPurchasesEnabled: Boolean,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onCreateInvite: () -> Unit,
    onRemoveMember: (Long) -> Unit,
    onLeave: () -> Unit,
    onShare: (String) -> Unit,
    onOpenPlans: () -> Unit,
    onBuyGift: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<FamilyMember?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.family_close),
                        )
                    }
                    Text(
                        stringResource(R.string.family_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.family_refresh),
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = LevikDimensions.ScreenHorizontalPadding,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(LevikDimensions.SectionSpacing),
                ) {
                    if (state.loading && state.family == null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    state.notice?.let { notice ->
                        item { NoticeText(stringResource(noticeText(notice))) }
                    }
                    state.error?.let { code ->
                        item { NoticeText(stringResource(growthErrorText(code)), error = true) }
                    }
                    state.family?.let { family ->
                        item {
                            FamilyCard(
                                state = state,
                                externalPurchasesEnabled = externalPurchasesEnabled,
                                onCreateInvite = onCreateInvite,
                                onShare = onShare,
                                onCopy = { copyLink(context, it) },
                                onRemove = { confirmRemove = it },
                                onLeave = { confirmLeave = true },
                                onOpenPlans = onOpenPlans,
                            )
                        }
                    }
                    state.referral?.let { referral ->
                        item {
                            SectionCard(
                                title = stringResource(R.string.friends_title),
                                body = stringResource(
                                    R.string.friends_body,
                                    referral.discountPercent,
                                    referral.rewardDays,
                                ),
                            ) {
                                Text(
                                    stringResource(R.string.friends_stats, referral.invited, referral.rewarded),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LinkActions(
                                    url = referral.url,
                                    shareText = stringResource(R.string.friends_share_text, referral.url),
                                    onShare = onShare,
                                    onCopy = { copyLink(context, it) },
                                )
                            }
                        }
                    }
                    if (state.gifts.isNotEmpty() || externalPurchasesEnabled) {
                        item {
                            SectionCard(
                                title = stringResource(R.string.gifts_title),
                                body = stringResource(R.string.gifts_body),
                            ) {
                                state.gifts.forEach { gift ->
                                    GiftRow(
                                        gift = gift,
                                        onShare = onShare,
                                        onCopy = { copyLink(context, it) },
                                    )
                                }
                                if (externalPurchasesEnabled) {
                                    OutlinedButton(
                                        onClick = onBuyGift,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = LevikDimensions.ButtonHeight),
                                    ) { Text(stringResource(R.string.gifts_buy)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { member ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.family_remove_title, member.name)) },
            text = { Text(stringResource(R.string.family_remove_body)) },
            confirmButton = {
                Button(onClick = {
                    confirmRemove = null
                    onRemoveMember(member.id)
                }) { Text(stringResource(R.string.family_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.invite_not_now)) }
            },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.family_leave_title)) },
            text = { Text(stringResource(R.string.family_leave_body)) },
            confirmButton = {
                Button(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text(stringResource(R.string.family_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.invite_not_now)) }
            },
        )
    }
}

@Composable
private fun FamilyCard(
    state: FamilyUiState,
    externalPurchasesEnabled: Boolean,
    onCreateInvite: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRemove: (FamilyMember) -> Unit,
    onLeave: () -> Unit,
    onOpenPlans: () -> Unit,
) {
    val family = state.family ?: return
    when (family.role) {
        "owner" -> {
            val used = family.usedSeats ?: family.members.size
            val free = (family.seats - used).coerceAtLeast(0)
            SectionCard(
                title = stringResource(R.string.family_owner_title),
                body = stringResource(R.string.family_owner_body, free, family.seats, formatDate(family.expiresAt)),
            ) {
                family.members.forEach { member ->
                    MemberRow(member = member, onRemove = onRemove.takeIf { member.role == "member" })
                }
                state.inviteLink?.let { link ->
                    InviteQr(link.url)
                    Text(
                        stringResource(R.string.family_invite_hint, formatDate(link.expiresAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinkActions(
                        url = link.url,
                        shareText = stringResource(R.string.family_share_text, link.url),
                        onShare = onShare,
                        onCopy = onCopy,
                    )
                }
                if (free > 0) {
                    Button(
                        onClick = onCreateInvite,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = LevikDimensions.ButtonHeight),
                    ) {
                        Text(
                            stringResource(
                                if (state.inviteLink == null) R.string.family_invite else R.string.family_invite_again,
                            ),
                        )
                    }
                }
            }
        }
        "member" -> SectionCard(
            title = stringResource(R.string.family_member_title, family.ownerName ?: stringResource(R.string.invite_someone)),
            body = stringResource(R.string.family_member_body, formatDate(family.expiresAt)),
        ) {
            OutlinedButton(
                onClick = onLeave,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = LevikDimensions.ButtonHeight),
            ) { Text(stringResource(R.string.family_leave)) }
        }
        else -> SectionCard(
            title = stringResource(R.string.family_none_title),
            body = stringResource(
                if (family.available) R.string.family_none_body else R.string.family_unavailable_body,
                family.seats,
            ),
        ) {
            if (family.available && externalPurchasesEnabled) {
                Button(
                    onClick = onOpenPlans,
                    modifier = Modifier.fillMaxWidth().heightIn(min = LevikDimensions.ButtonHeight),
                ) { Text(stringResource(R.string.family_choose_plan)) }
            }
        }
    }
}

@Composable
private fun MemberRow(member: FamilyMember, onRemove: ((FamilyMember) -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(member.name, fontWeight = FontWeight.SemiBold)
            Text(
                if (member.lastActiveAt != null) {
                    stringResource(R.string.family_member_devices_seen, member.devices, formatDate(member.lastActiveAt))
                } else {
                    stringResource(R.string.family_member_devices, member.devices)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            TextButton(onClick = { onRemove(member) }) { Text(stringResource(R.string.family_remove)) }
        } else {
            Text(
                stringResource(R.string.family_owner_badge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GiftRow(gift: GiftLink, onShare: (String) -> Unit, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.gift_line, gift.tariffTitle, gift.months), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(
                when (gift.status) {
                    "active" -> R.string.gift_status_active
                    "used" -> R.string.gift_status_used
                    else -> R.string.gift_status_expired
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        gift.url?.let { url ->
            LinkActions(
                url = url,
                shareText = stringResource(R.string.gift_share_text, url),
                onShare = onShare,
                onCopy = onCopy,
            )
        }
    }
}

@Composable
private fun InviteQr(url: String) {
    val bitmap = remember(url) { activationQrBitmap(url, size = 512).asImageBitmap() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = androidx.compose.ui.graphics.Color.White) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.family_invite_qr),
                modifier = Modifier.padding(12.dp).size(180.dp),
            )
        }
    }
}

@Composable
private fun LinkActions(url: String, shareText: String, onShare: (String) -> Unit, onCopy: (String) -> Unit) {
    Text(url, style = MaterialTheme.typography.bodySmall, color = LevikBlue)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onShare(shareText) },
            modifier = Modifier.weight(1f).heightIn(min = LevikDimensions.ButtonHeight),
        ) { Text(stringResource(R.string.growth_share)) }
        OutlinedButton(
            onClick = { onCopy(url) },
            modifier = Modifier.weight(1f).heightIn(min = LevikDimensions.ButtonHeight),
        ) {
            Icon(painter = painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.growth_copy))
        }
    }
}

@Composable
private fun SectionCard(title: String, body: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LevikDimensions.CardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun NoticeText(text: String, error: Boolean = false) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else LevikBlue,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Levik VPN", url))
    Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
}

private fun formatDate(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    return runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrDefault("—")
}

@StringRes
private fun inviteTitle(kind: String): Int = when (kind) {
    "family" -> R.string.invite_family_title
    "gift" -> R.string.invite_gift_title
    else -> R.string.invite_referral_title
}

@StringRes
private fun acceptLabel(kind: String): Int = when (kind) {
    "family" -> R.string.invite_family_accept
    "gift" -> R.string.invite_gift_accept
    else -> R.string.invite_referral_accept
}

@StringRes
private fun claimedTitle(kind: String): Int = when (kind) {
    "family" -> R.string.invite_family_done_title
    "gift" -> R.string.invite_gift_done_title
    else -> R.string.invite_referral_done_title
}

@StringRes
private fun claimedBody(kind: String): Int = when (kind) {
    "referral" -> R.string.invite_referral_done_body
    else -> R.string.invite_access_done_body
}

@StringRes
private fun noticeText(notice: FamilyNotice): Int = when (notice) {
    FamilyNotice.MEMBER_REMOVED -> R.string.family_member_removed
    FamilyNotice.LEFT -> R.string.family_left
}

@StringRes
internal fun growthErrorText(code: String): Int = when (code) {
    "invite_not_found" -> R.string.growth_error_invite_not_found
    "invite_used" -> R.string.growth_error_invite_used
    "own_invite" -> R.string.growth_error_own_invite
    "not_first_purchase" -> R.string.growth_error_not_first_purchase
    "already_invited" -> R.string.growth_error_already_invited
    "already_in_family" -> R.string.growth_error_already_in_family
    "family_full" -> R.string.growth_error_family_full
    "family_expired" -> R.string.growth_error_family_expired
    "owner_cannot_leave" -> R.string.growth_error_owner_cannot_leave
    "gift_delivery_failed" -> R.string.growth_error_gift_delivery_failed
    "rate_limited" -> R.string.growth_error_rate_limited
    GrowthController.ACCOUNT_REQUIRED -> R.string.growth_error_account_required
    GrowthController.NETWORK -> R.string.growth_error_network
    else -> R.string.growth_error_generic
}
