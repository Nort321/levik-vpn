package com.leviknet.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R
import com.leviknet.vpn.ui.theme.LevikBlue

internal fun openDistributionPlans(viewModel: AppViewModel) {
    viewModel.openPurchaseFlow()
}

@Composable
internal fun DistributionRenewPlanButton(
    onOpenPlans: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onOpenPlans,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LevikBlue,
            contentColor = Color.White,
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_crown),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color.White,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.renew_plan_btn),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
internal fun DistributionAddonActions(
    slotAddon: Boolean,
    trafficAddon: Boolean,
    onOpenPlans: () -> Unit,
) {
    if (!slotAddon && !trafficAddon) return

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (slotAddon) {
            OutlinedButton(
                onClick = onOpenPlans,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.subscription_buy_slots_btn),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (trafficAddon) {
            OutlinedButton(
                onClick = onOpenPlans,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.subscription_buy_traffic_btn),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
