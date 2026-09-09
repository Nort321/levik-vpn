package com.leviknet.vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.R
import com.leviknet.vpn.core.network.DeviceItem
import com.leviknet.vpn.core.network.DeviceSummary
import com.leviknet.vpn.core.network.SubscriptionSummary

enum class ProblemAction { RETRY, DEVICES, SUBSCRIPTIONS, PLANS, LOGIN, SUPPORT, SETTINGS, CLOCK, SERVERS, DIAGNOSTICS }

internal fun problemActions(problem: AppProblem, subscription: SubscriptionSummary?): List<ProblemAction> {
    val primary = when (problem.reason) {
        ProblemReason.DEVICE_LIMIT -> if (subscription?.actions?.revokeDevice == true) ProblemAction.DEVICES else ProblemAction.SUBSCRIPTIONS
        ProblemReason.SUBSCRIPTION, ProblemReason.TRAFFIC, ProblemReason.TRIAL -> ProblemAction.SUBSCRIPTIONS
        ProblemReason.SESSION, ProblemReason.LOGIN -> ProblemAction.LOGIN
        ProblemReason.CLOCK -> ProblemAction.CLOCK
        ProblemReason.PERMISSION, ProblemReason.NOTIFICATIONS, ProblemReason.LOCATION -> ProblemAction.SETTINGS
        ProblemReason.PAYMENT, ProblemReason.ORDER -> ProblemAction.SUBSCRIPTIONS
        ProblemReason.ATTESTATION, ProblemReason.VPN_CORE, ProblemReason.REQUEST, ProblemReason.UNKNOWN -> ProblemAction.SUPPORT
        ProblemReason.UNSUPPORTED, ProblemReason.VPN_NETWORK_REQUIREMENT -> ProblemAction.SERVERS
        else -> ProblemAction.RETRY
    }
    return buildList {
        add(primary)
        if (problem.reason == ProblemReason.DEVICE_LIMIT) add(ProblemAction.SUBSCRIPTIONS)
        if (problem.reason in setOf(ProblemReason.NETWORK, ProblemReason.TIMEOUT, ProblemReason.VPN_NETWORK)) add(ProblemAction.DIAGNOSTICS)
        if (problem.reason == ProblemReason.VPN_NETWORK) add(ProblemAction.SERVERS)
        if (BuildConfig.EXTERNAL_PURCHASES_ENABLED && problem.reason in setOf(
                ProblemReason.DEVICE_LIMIT, ProblemReason.SUBSCRIPTION, ProblemReason.TRAFFIC, ProblemReason.TRIAL,
            )) add(ProblemAction.PLANS)
        if (size < 3 && primary != ProblemAction.SUPPORT) add(ProblemAction.SUPPORT)
    }.distinct().take(3)
}

@Composable
internal fun ProblemDialog(
    problem: AppProblem,
    subscription: SubscriptionSummary?,
    busy: Boolean,
    onAction: (ProblemAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(problem.reason.titleResource()), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(problem.reason.bodyResource()))
                if (subscription != null && problem.reason in setOf(
                        ProblemReason.DEVICE_LIMIT, ProblemReason.SUBSCRIPTION, ProblemReason.TRAFFIC,
                    )) {
                    Text(subscription.title, fontWeight = FontWeight.SemiBold)
                    if (problem.reason == ProblemReason.DEVICE_LIMIT) {
                        SubscriptionSlotUsage(subscription)
                    }
                }
                if (problem.reason == ProblemReason.DEVICE_LIMIT) {
                    problem.details?.let { details ->
                        val component = when (details.component) {
                            "mobile" -> R.string.problem_mobile_slots
                            "regular" -> R.string.problem_regular_slots
                            else -> null
                        }
                        if (component != null) {
                            Text(stringResource(R.string.problem_blocked_component, stringResource(component)),
                                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                problemActions(problem, subscription).forEachIndexed { index, action ->
                    if (index == 0) {
                        Button(onClick = { onAction(action) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            ProblemActionLabel(action)
                        }
                    } else {
                        TextButton(onClick = { onAction(action) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            ProblemActionLabel(action)
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun ProblemActionLabel(action: ProblemAction) {
    val (label, icon) = when (action) {
        ProblemAction.RETRY -> R.string.problem_action_retry to R.drawable.ic_refresh
        ProblemAction.DEVICES -> R.string.problem_action_devices to R.drawable.ic_profile
        ProblemAction.SUBSCRIPTIONS -> R.string.problem_action_subscriptions to R.drawable.ic_home
        ProblemAction.PLANS -> R.string.problem_action_plans to R.drawable.ic_crown
        ProblemAction.LOGIN -> R.string.problem_action_login to R.drawable.ic_login
        ProblemAction.SUPPORT -> R.string.problem_action_support to R.drawable.ic_telegram
        ProblemAction.SETTINGS -> R.string.problem_action_settings to R.drawable.ic_privacy
        ProblemAction.CLOCK -> R.string.problem_action_clock to R.drawable.ic_usage
        ProblemAction.SERVERS -> R.string.problem_action_servers to R.drawable.ic_servers
        ProblemAction.DIAGNOSTICS -> R.string.diagnostics_btn to R.drawable.ic_speed
    }
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(label))
}

@Composable
internal fun SubscriptionSlotUsage(subscription: SubscriptionSummary) {
    val components = subscription.components
    if (components == null) {
        SlotUsage(stringResource(R.string.devices_dialog_title), subscription.devices)
    } else {
        SlotUsage(stringResource(R.string.problem_regular_slots), components.regular.devices)
        SlotUsage(stringResource(R.string.problem_mobile_slots), components.mobile.devices)
    }
}

@Composable
private fun SlotUsage(label: String, devices: DeviceSummary) {
    val full = devices.limit > 0 && devices.used >= devices.limit
    Text(
        stringResource(R.string.problem_slot_usage, label, devices.used, devices.limit) +
            if (full) " · " + stringResource(R.string.problem_slots_full) else "",
        style = MaterialTheme.typography.bodyMedium,
        color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun DeviceItem.connectionClient(): String? {
    client?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(120) }
    // Compatibility with older snapshots which embed the client in the label.
    return listOf("LevikVPN", "Levik VPN", "Happ", "Hiddify", "v2rayNG", "NekoBox", "Clash", "sing-box", "Streisand")
        .firstOrNull { label.contains(it, ignoreCase = true) }
}

private fun ProblemReason.titleResource(): Int = when (this) {
    ProblemReason.NETWORK -> R.string.problem_network_title
    ProblemReason.TIMEOUT -> R.string.problem_timeout_title
    ProblemReason.SERVICE -> R.string.problem_service_title
    ProblemReason.PROFILE -> R.string.problem_profile_title
    ProblemReason.DEVICE_LIMIT -> R.string.problem_device_limit_title
    ProblemReason.SUBSCRIPTION -> R.string.problem_subscription_title
    ProblemReason.TRAFFIC -> R.string.problem_traffic_title
    ProblemReason.SESSION -> R.string.problem_session_title
    ProblemReason.LOGIN -> R.string.problem_login_title
    ProblemReason.ATTESTATION -> R.string.problem_attestation_title
    ProblemReason.CLOCK -> R.string.problem_clock_title
    ProblemReason.RATE_LIMIT -> R.string.problem_rate_limit_title
    ProblemReason.TRIAL -> R.string.problem_trial_title
    ProblemReason.PAYMENT -> R.string.problem_payment_title
    ProblemReason.ORDER -> R.string.problem_order_title
    ProblemReason.PERMISSION -> R.string.problem_permission_title
    ProblemReason.NOTIFICATIONS -> R.string.problem_notifications_title
    ProblemReason.LOCATION -> R.string.problem_location_title
    ProblemReason.DEVICE_REVOKE -> R.string.problem_device_revoke_title
    ProblemReason.UNSUPPORTED -> R.string.problem_unsupported_title
    ProblemReason.REQUEST -> R.string.problem_request_title
    ProblemReason.UNKNOWN -> R.string.problem_unknown_title
    ProblemReason.VPN_CORE -> R.string.problem_vpn_core_title
    ProblemReason.VPN_NETWORK -> R.string.problem_vpn_network_title
    ProblemReason.VPN_NETWORK_REQUIREMENT -> R.string.problem_vpn_network_requirement_title
}

private fun ProblemReason.bodyResource(): Int = when (this) {
    ProblemReason.NETWORK -> R.string.problem_network_body
    ProblemReason.TIMEOUT -> R.string.problem_timeout_body
    ProblemReason.SERVICE -> R.string.problem_service_body
    ProblemReason.PROFILE -> R.string.problem_profile_body
    ProblemReason.DEVICE_LIMIT -> R.string.problem_device_limit_body
    ProblemReason.SUBSCRIPTION -> R.string.problem_subscription_body
    ProblemReason.TRAFFIC -> R.string.problem_traffic_body
    ProblemReason.SESSION -> R.string.problem_session_body
    ProblemReason.LOGIN -> R.string.problem_login_body
    ProblemReason.ATTESTATION -> R.string.problem_attestation_body
    ProblemReason.CLOCK -> R.string.problem_clock_body
    ProblemReason.RATE_LIMIT -> R.string.problem_rate_limit_body
    ProblemReason.TRIAL -> R.string.problem_trial_body
    ProblemReason.PAYMENT -> R.string.problem_payment_body
    ProblemReason.ORDER -> R.string.problem_order_body
    ProblemReason.PERMISSION -> R.string.problem_permission_body
    ProblemReason.NOTIFICATIONS -> R.string.problem_notifications_body
    ProblemReason.LOCATION -> R.string.problem_location_body
    ProblemReason.DEVICE_REVOKE -> R.string.problem_device_revoke_body
    ProblemReason.UNSUPPORTED -> R.string.problem_unsupported_body
    ProblemReason.REQUEST -> R.string.problem_request_body
    ProblemReason.UNKNOWN -> R.string.problem_unknown_body
    ProblemReason.VPN_CORE -> R.string.problem_vpn_core_body
    ProblemReason.VPN_NETWORK -> R.string.problem_vpn_network_body
    ProblemReason.VPN_NETWORK_REQUIREMENT -> R.string.problem_vpn_network_requirement_body
}
