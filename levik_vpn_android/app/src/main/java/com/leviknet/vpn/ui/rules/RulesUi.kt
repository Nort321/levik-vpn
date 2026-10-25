package com.leviknet.vpn.ui.rules

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.leviknet.vpn.R
import com.leviknet.vpn.core.network.DomainRule
import com.leviknet.vpn.core.network.RuleCategory
import com.leviknet.vpn.core.platform.RuleAction
import com.leviknet.vpn.ui.theme.LevikBlue
import com.leviknet.vpn.ui.theme.LevikDimensions

/** The profile's way into the account's rules. */
@Composable
internal fun RulesEntryCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_web),
                contentDescription = null,
                tint = LevikBlue,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rules_entry_title), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.rules_entry_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Categories and the user's own sites; every app, the extension and the router apply them. */
@Composable
internal fun RulesScreen(
    state: RulesUiState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onSetCategory: (String, RuleAction?) -> Unit,
    onSetDomain: (String, RuleAction) -> Boolean,
    onRemoveDomain: (String) -> Unit,
    onOpenServiceRoutes: () -> Unit,
    serviceRouteState: String,
    serviceRouteDetail: String?,
    hasAppExclusions: Boolean,
    onOpenAppExclusions: () -> Unit,
) {
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.rules_close),
                        )
                    }
                    Text(
                        stringResource(R.string.rules_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.rules_refresh),
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = LevikDimensions.ScreenHorizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(LevikDimensions.SectionSpacing),
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (russian) "Маршруты сервисов" else "Service routes", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (russian) "Страна или сервер для сайта, маски *.ru и настройки для выбранных устройств. Открывается личный кабинет."
                                    else "Country or exit for a website, *.ru masks, and settings for selected devices. Opens your account.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val status = if (serviceRouteDetail == "cache_limit") {
                                    if (russian) "Достигнут лимит сохранённых подписок (100). Новая настройка не подтверждена; обратитесь в поддержку."
                                    else "The saved subscription limit (100) has been reached. The new setting is unconfirmed; contact support."
                                } else if (russian) when (serviceRouteState) {
                                    "applied" -> "Настройки применены. Состояние отдельных маршрутов — в кабинете."
                                    "suspended" -> "Не применяется в текущем способе подключения."
                                    "pending" -> "Ожидает применения."
                                    "error" -> "Новая настройка не применена; действует предыдущая конфигурация."
                                    "unsupported" -> "Текущий клиент или способ подключения пока не поддерживает маршруты сервисов."
                                    else -> "Настройки сохраняются в облаке и применяются обычным VPN."
                                } else when (serviceRouteState) {
                                    "applied" -> "Settings applied. Per-route status is available in your account."
                                    "suspended" -> "Inactive with the current connection method."
                                    "pending" -> "Waiting to apply."
                                    "error" -> "New settings were not applied; the previous configuration remains active."
                                    "unsupported" -> "The current client or connection method does not support service routes yet."
                                    else -> "Settings are saved in the cloud and applied by the ordinary VPN."
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall)
                                if (hasAppExclusions) {
                                    Text(
                                        if (russian) "Правила сайтов не действуют в приложениях, исключённых из VPN."
                                        else "Website rules do not apply to apps excluded from the VPN.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(onClick = onOpenAppExclusions) {
                                        Text(if (russian) "Проверить исключения приложений" else "Review app exclusions")
                                    }
                                }
                                OutlinedButton(onClick = onOpenServiceRoutes) {
                                    Text(if (russian) "Настроить маршруты и устройства" else "Configure routes and devices")
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.rules_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { RulesStatus(state) }
                    val rules = state.rules
                    if (rules == null) {
                        if (state.loading) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        return@LazyColumn
                    }
                    if (state.categories.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.rules_categories_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(state.categories, key = { it.id }) { category ->
                            CategoryCard(
                                category = category,
                                russian = russian,
                                action = RuleAction.of(rules.categories[category.id]),
                                enabled = !state.busy,
                                onChange = { onSetCategory(category.id, it) },
                            )
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.rules_sites_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.rules_sites_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { AddSite(enabled = !state.busy, onAdd = onSetDomain) }
                    if (rules.domains.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.rules_sites_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(rules.domains, key = { it.domain }) { rule ->
                        SiteRow(
                            rule = rule,
                            enabled = !state.busy,
                            onFlip = { action -> onSetDomain(rule.domain, action) },
                            onRemove = { onRemoveDomain(rule.domain) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesStatus(state: RulesUiState) {
    val error = state.error
    val saved = state.saved
    val text = when {
        error != null -> stringResource(rulesErrorText(error))
        saved != null -> when (saved.action) {
            RuleAction.DIRECT -> stringResource(R.string.rules_saved_direct, saved.domain)
            RuleAction.PROXY -> stringResource(R.string.rules_saved_proxy, saved.domain)
            null -> stringResource(R.string.rules_saved_removed, saved.domain)
        }
        else -> return
    }
    Text(
        text = text,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun CategoryCard(
    category: RuleCategory,
    russian: Boolean,
    action: RuleAction?,
    enabled: Boolean,
    onChange: (RuleAction?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(category.title.localized(russian), fontWeight = FontWeight.SemiBold)
            if (category.apps.isNotEmpty()) {
                Text(
                    category.apps.joinToString(", ") { it.localized(russian) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to R.string.rules_like_mode, RuleAction.DIRECT to R.string.rules_direct, RuleAction.PROXY to R.string.rules_proxy)
                    .forEach { (choice, label) ->
                        FilterChip(
                            selected = action == choice,
                            onClick = { if (action != choice) onChange(choice) },
                            enabled = enabled,
                            label = { Text(stringResource(label)) },
                        )
                    }
            }
        }
    }
}

@Composable
private fun AddSite(enabled: Boolean, onAdd: (String, RuleAction) -> Boolean) {
    var site by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf(RuleAction.PROXY) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = site,
            onValueChange = { site = it.take(MAX_SITE_INPUT) },
            label = { Text(stringResource(R.string.rules_site_label)) },
            placeholder = { Text("example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RuleAction.PROXY to R.string.rules_proxy, RuleAction.DIRECT to R.string.rules_direct).forEach { (choice, label) ->
                FilterChip(
                    selected = action == choice,
                    onClick = { action = choice },
                    enabled = enabled,
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Button(
            onClick = { if (onAdd(site, action)) site = "" },
            enabled = enabled && site.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.rules_add))
        }
    }
}

@Composable
private fun SiteRow(rule: DomainRule, enabled: Boolean, onFlip: (RuleAction) -> Unit, onRemove: () -> Unit) {
    val direct = rule.action == RuleAction.DIRECT.wire
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.domain, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (direct) R.string.rules_site_direct else R.string.rules_site_proxy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onFlip(if (direct) RuleAction.PROXY else RuleAction.DIRECT) }, enabled = enabled) {
                Text(stringResource(if (direct) R.string.rules_proxy else R.string.rules_direct))
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.rules_remove, rule.domain),
                )
            }
        }
    }
}

/** A site shared from a browser or a messenger: through the VPN or directly, on every device. */
@Composable
internal fun SharedSiteDialog(
    site: String,
    busy: Boolean,
    error: String?,
    onChoose: (RuleAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_shared_title, site)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rules_shared_body))
                if (error != null) {
                    Text(
                        stringResource(rulesErrorText(error)),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onChoose(RuleAction.DIRECT) }, enabled = !busy) {
                    Text(stringResource(R.string.rules_direct))
                }
                Button(onClick = { onChoose(RuleAction.PROXY) }, enabled = !busy) {
                    Text(stringResource(R.string.rules_proxy))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
        },
    )
}

@StringRes
internal fun rulesErrorText(code: String): Int = when (code) {
    RulesController.INVALID_DOMAIN -> R.string.rules_error_invalid_domain
    "too_many_rules" -> R.string.rules_error_too_many
    "rate_limited" -> R.string.rules_error_rate_limited
    RulesController.ACCOUNT_REQUIRED -> R.string.rules_error_account
    RulesController.NETWORK -> R.string.rules_error_network
    else -> R.string.rules_error_unknown
}

private const val MAX_SITE_INPUT = 300
