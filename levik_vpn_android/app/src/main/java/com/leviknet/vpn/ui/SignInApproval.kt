package com.leviknet.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.leviknet.vpn.R
import com.leviknet.vpn.core.auth.PendingApproval
import com.leviknet.vpn.ui.theme.LevikBlue

/** The number a new sign-in shows; the user picks it on a device that is already signed in. */
@Composable
internal fun LoginMatchNumber(number: Int, hint: String) {
    val description = stringResource(R.string.login_match_content_description, number)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.login_match_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = number.toString(),
                fontSize = 56.sp,
                lineHeight = 60.sp,
                fontWeight = FontWeight.ExtraBold,
                color = LevikBlue,
                modifier = Modifier.semantics { contentDescription = description },
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Asks about a sign-in on another device: the three numbers, "This is not me"
 * and "Later". A wrong number declines it, docs/app-platform.md.
 */
@Composable
internal fun SignInApprovalDialog(
    approval: PendingApproval,
    answering: Boolean,
    onAnswer: (Int?) -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!answering) onLater() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.approval_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.approval_device, approval.platformLabel, approval.deviceName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.approval_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    approval.choices.forEach { choice ->
                        val description = stringResource(R.string.login_match_content_description, choice)
                        OutlinedButton(
                            onClick = { onAnswer(choice) },
                            enabled = !answering,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 64.dp)
                                .semantics { contentDescription = description },
                        ) {
                            Text(choice.toString(), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(null) }, enabled = !answering) {
                Text(stringResource(R.string.approval_not_me), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, enabled = !answering) {
                Text(stringResource(R.string.approval_later))
            }
        },
    )
}
