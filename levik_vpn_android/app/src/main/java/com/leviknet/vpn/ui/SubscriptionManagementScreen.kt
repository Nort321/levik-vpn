package com.leviknet.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R
import com.leviknet.vpn.core.network.CatalogPaymentMethod
import com.leviknet.vpn.core.network.CatalogResponse
import com.leviknet.vpn.core.network.CatalogTariff
import com.leviknet.vpn.core.network.MobileAccountResponse
import com.leviknet.vpn.core.network.OrderSummary
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.ui.theme.LevikBlue
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun SubscriptionManagementScreen(
    modifier: Modifier,
    account: MobileAccountResponse?,
    catalog: CatalogResponse?,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPurchase: (String, String?, String?, Int?, String) -> Unit,
    onContinueOrder: (Long) -> Unit,
) {
    val paymentMethods = catalog?.paymentMethods.orEmpty()
    var paymentMethodId by remember(catalog) {
        mutableStateOf(paymentMethods.firstOrNull()?.id.orEmpty())
    }
    val selectedPaymentMethod = paymentMethods.firstOrNull { it.id == paymentMethodId }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 12.dp,
            end = 20.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = !loading) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.subscription_management_back),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.subscription_management_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = account?.user?.userLabel
                            ?: stringResource(R.string.profile_offline_access),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.content_refresh),
                    )
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = LevikBlue.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, LevikBlue.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield_check),
                        contentDescription = null,
                        tint = LevikBlue,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.subscription_management_no_telegram),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.subscription_management_current)) }

        if (account?.subscriptions.isNullOrEmpty()) {
            item {
                EmptyManagementCard(
                    title = stringResource(R.string.subscription_management_no_subscription),
                    description = stringResource(R.string.subscription_management_no_subscription_description),
                )
            }
        } else {
            items(account.subscriptions, key = SubscriptionSummary::uuid) { subscription ->
                ManagedSubscriptionCard(
                    subscription = subscription,
                    catalog = catalog,
                    paymentMethodId = paymentMethodId,
                    loading = loading,
                    onPurchase = onPurchase,
                )
            }
        }

        item { SectionTitle(stringResource(R.string.subscription_management_payment_method)) }

        when {
            loading && catalog == null -> item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            catalog == null -> item {
                EmptyManagementCard(
                    title = stringResource(R.string.subscription_management_catalog_error),
                    description = stringResource(R.string.subscription_management_catalog_error_description),
                    actionLabel = stringResource(R.string.login_retry),
                    onAction = onRefresh,
                )
            }
            paymentMethods.isEmpty() -> item {
                EmptyManagementCard(
                    title = stringResource(R.string.subscription_management_payment_unavailable),
                    description = stringResource(R.string.subscription_management_payment_unavailable_description),
                )
            }
            else -> item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        paymentMethods.forEach { method ->
                            PaymentMethodRow(
                                method = method,
                                selected = method.id == paymentMethodId,
                                enabled = !loading,
                                onSelected = { paymentMethodId = method.id },
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.subscription_management_plans)) }

        val tariffs = catalog?.tariffs.orEmpty().filter {
            it.purchaseEnabled && it.periods.isNotEmpty()
        }
        if (catalog != null && tariffs.isEmpty()) {
            item {
                EmptyManagementCard(
                    title = stringResource(R.string.subscription_management_plans_unavailable),
                    description = stringResource(R.string.subscription_management_plans_unavailable_description),
                )
            }
        } else {
            items(tariffs, key = CatalogTariff::id) { tariff ->
                TariffPurchaseCard(
                    tariff = tariff,
                    paymentMethodId = selectedPaymentMethod?.id.orEmpty(),
                    loading = loading,
                    onPurchase = onPurchase,
                )
            }
        }

        item { SectionTitle(stringResource(R.string.subscription_management_orders)) }

        if (account?.orders.isNullOrEmpty()) {
            item {
                EmptyManagementCard(
                    title = stringResource(R.string.subscription_management_orders_empty),
                    description = stringResource(R.string.subscription_management_orders_empty_description),
                )
            }
        } else {
            items(account.orders, key = OrderSummary::id) { order ->
                OrderCard(
                    order = order,
                    loading = loading,
                    onContinue = { onContinueOrder(order.id) },
                )
            }
        }
    }
}

@Composable
private fun ManagedSubscriptionCard(
    subscription: SubscriptionSummary,
    catalog: CatalogResponse?,
    paymentMethodId: String,
    loading: Boolean,
    onPurchase: (String, String?, String?, Int?, String) -> Unit,
) {
    val tariff = catalog?.tariffs?.firstOrNull { it.id == subscription.tariffId }
    var renewalMonths by remember(subscription.uuid, tariff) {
        mutableIntStateOf(tariff?.periods?.firstOrNull()?.months ?: 0)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = subscription.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subscriptionStatusLabel(subscription.status),
                        color = subscriptionStatusColor(subscription.status),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_crown),
                    contentDescription = null,
                    tint = LevikBlue,
                    modifier = Modifier.size(24.dp),
                )
            }
            ManagementMetric(
                label = stringResource(R.string.profile_expires),
                value = subscription.expireAt?.let(::formatDate)
                    ?: stringResource(R.string.profile_no_expiry),
            )
            ManagementMetric(
                label = stringResource(R.string.profile_traffic),
                value = stringResource(
                    R.string.subscription_management_usage,
                    formatBytes(subscription.traffic.usedBytes),
                    formatLimitBytes(subscription.traffic.limitBytes),
                ),
            )
            ManagementMetric(
                label = stringResource(R.string.profile_devices),
                value = "${subscription.devices.used} / ${subscription.devices.limit}",
            )

            if (subscription.actions.renew && tariff != null) {
                Text(
                    text = stringResource(R.string.subscription_management_renewal_period),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tariff.periods.forEach { period ->
                        FilterChip(
                            selected = renewalMonths == period.months,
                            onClick = { renewalMonths = period.months },
                            enabled = !loading,
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.subscription_management_period_price,
                                        period.title,
                                        period.amountRub,
                                    ),
                                )
                            },
                        )
                    }
                }
                Button(
                    onClick = {
                        onPurchase(
                            "access_renewal",
                            subscription.uuid,
                            tariff.id,
                            renewalMonths,
                            paymentMethodId,
                        )
                    },
                    enabled = !loading && renewalMonths > 0 && paymentMethodId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subscription_management_renew))
                }
            }

            if (subscription.actions.slotAddon || subscription.actions.trafficAddon) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (subscription.actions.slotAddon) {
                        OutlinedButton(
                            onClick = {
                                onPurchase(
                                    "slot_addon",
                                    subscription.uuid,
                                    null,
                                    null,
                                    paymentMethodId,
                                )
                            },
                            enabled = !loading && paymentMethodId.isNotBlank(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_profile),
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.subscription_buy_slots_btn))
                        }
                    }
                    if (subscription.actions.trafficAddon) {
                        OutlinedButton(
                            onClick = {
                                onPurchase(
                                    "traffic_addon",
                                    subscription.uuid,
                                    null,
                                    null,
                                    paymentMethodId,
                                )
                            },
                            enabled = !loading && paymentMethodId.isNotBlank(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_usage),
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.subscription_buy_traffic_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TariffPurchaseCard(
    tariff: CatalogTariff,
    paymentMethodId: String,
    loading: Boolean,
    onPurchase: (String, String?, String?, Int?, String) -> Unit,
) {
    var months by remember(tariff.id) { mutableIntStateOf(tariff.periods.first().months) }
    val selectedPeriod = tariff.periods.firstOrNull { it.months == months }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = tariff.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = tariff.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(
                        R.string.subscription_management_traffic_limit,
                        formatLimitBytes(tariff.trafficLimitBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.subscription_management_device_limit,
                        tariff.deviceLimit,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tariff.periods.forEach { period ->
                    FilterChip(
                        selected = months == period.months,
                        onClick = { months = period.months },
                        enabled = !loading,
                        label = {
                            Text(
                                stringResource(
                                    R.string.subscription_management_period_price,
                                    period.title,
                                    period.amountRub,
                                ),
                            )
                        },
                    )
                }
            }
            Button(
                onClick = {
                    onPurchase(
                        "access_purchase",
                        null,
                        tariff.id,
                        selectedPeriod?.months,
                        paymentMethodId,
                    )
                },
                enabled = !loading && selectedPeriod != null && paymentMethodId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LevikBlue),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_crown),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = selectedPeriod?.let {
                        stringResource(R.string.subscription_management_buy_for, it.amountRub)
                    } ?: stringResource(R.string.purchase_continue),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PaymentMethodRow(
    method: CatalogPaymentMethod,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelected,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(method.title, fontWeight = FontWeight.SemiBold)
            if (method.feePercent > 0.0) {
                Text(
                    text = stringResource(
                        R.string.subscription_management_payment_fee,
                        method.feePercent,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: OrderSummary,
    loading: Boolean,
    onContinue: () -> Unit,
) {
    val pending = order.status.lowercase(Locale.ROOT) in setOf("pending", "pending_payment")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(orderKindLabel(order.kind), fontWeight = FontWeight.Bold)
                    Text(
                        text = formatDate(order.createdAt),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.subscription_management_amount,
                        NumberFormat.getIntegerInstance().format(order.amountRub),
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = orderStatusLabel(order.status),
                color = orderStatusColor(order.status),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (pending) {
                Button(
                    onClick = onContinue,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_web),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subscription_management_continue_payment))
                }
            }
        }
    }
}

@Composable
private fun ManagementMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EmptyManagementCard(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(2.dp))
                OutlinedButton(onClick = onAction) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun subscriptionStatusLabel(status: String): String = stringResource(
    when (status.lowercase(Locale.ROOT)) {
        "active" -> R.string.active
        "expired" -> R.string.expired
        else -> R.string.inactive
    },
)

@Composable
private fun subscriptionStatusColor(status: String) = when (status.lowercase(Locale.ROOT)) {
    "active" -> MaterialTheme.colorScheme.primary
    "expired" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun orderKindLabel(kind: String): String = stringResource(
    when (kind.lowercase(Locale.ROOT)) {
        "access_purchase" -> R.string.subscription_management_order_purchase
        "access_renewal" -> R.string.subscription_management_order_renewal
        "slot_addon" -> R.string.subscription_management_order_slots
        "traffic_addon" -> R.string.subscription_management_order_traffic
        else -> R.string.subscription_management_order_default
    },
)

@Composable
private fun orderStatusLabel(status: String): String = stringResource(
    when (status.lowercase(Locale.ROOT)) {
        "pending", "pending_payment" -> R.string.subscription_management_order_pending
        "paid", "completed", "succeeded" -> R.string.subscription_management_order_paid
        "canceled", "cancelled" -> R.string.subscription_management_order_cancelled
        "expired" -> R.string.expired
        else -> R.string.subscription_management_order_processing
    },
)

@Composable
private fun orderStatusColor(status: String) = when (status.lowercase(Locale.ROOT)) {
    "paid", "completed", "succeeded" -> MaterialTheme.colorScheme.primary
    "canceled", "cancelled", "expired" -> MaterialTheme.colorScheme.error
    else -> LevikBlue
}
