package com.leviknet.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.Locale
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leviknet.vpn.R
import com.leviknet.vpn.core.auth.ChallengeAuthorization
import com.leviknet.vpn.core.logger.LogEntry
import com.leviknet.vpn.core.network.DiagnosticReport
import com.leviknet.vpn.core.network.LevikStatusSnapshot
import com.leviknet.vpn.core.network.MobileAccountResponse
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.core.network.TrafficSummary
import com.leviknet.vpn.data.AntiDpiPreset
import com.leviknet.vpn.data.DailyTraffic
import com.leviknet.vpn.data.DnsProvider
import com.leviknet.vpn.data.RoutingPreset
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.data.SplitTunnelMode
import com.leviknet.vpn.data.ThemeMode
import com.leviknet.vpn.data.isActiveAt
import com.leviknet.vpn.ui.theme.*
import com.leviknet.vpn.vpn.PreparedTunnelProfile
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnFailure
import com.leviknet.vpn.vpn.VpnSnapshot
import com.leviknet.vpn.vpn.isMobileServer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.leviknet.vpn.core.network.ReferralSummary

@Composable
fun LevikVpnApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.localized()
    val context = LocalContext.current

    var showPauseDialog by remember { mutableStateOf(false) }
    var showAntiDpiDialog by remember { mutableStateOf(false) }
    var showSplitTunnelDialog by remember { mutableStateOf(false) }
    var showAppSelectorDialog by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showCustomRoutingDialog by remember { mutableStateOf(false) }
    var showRoutingPresetDialog by remember { mutableStateOf(false) }
    var showWifiProtectionDialog by remember { mutableStateOf(false) }
    var showKillSwitchDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }
    var selectedSubscriptionForDevices by remember { mutableStateOf<SubscriptionSummary?>(null) }
    var showClearTrafficHistoryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            when {
                state.session == SessionStatus.Loading -> LoadingScreen()
                state.session == SessionStatus.SignedOut && state.profile == null -> LoginScreen(
                    login = state.login,
                    snackbarHostState = snackbarHostState,
                    onTelegramLogin = viewModel::beginTelegramLogin,
                    onWebsiteLogin = viewModel::beginWebsiteLogin,
                    onDeviceTrial = viewModel::activateDeviceTrial,
                    onLteTrial = viewModel::activateLteTrial,
                    onFreeProxy = viewModel::openFreeProxyBot,
                    onOpenAgain = viewModel::openLoginUriAgain,
                    onRetry = viewModel::retryLogin,
                    onPrivacyPolicy = viewModel::openPrivacyPolicy,
                )
                else -> MainContent(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onTabSelected = viewModel::selectTab,
                    onConnect = viewModel::connectOrDisconnect,
                    onTrial = viewModel::activateLteTrial,
                    onOpenPauseVpn = { showPauseDialog = true },
                    onResumeVpn = viewModel::resumeVpn,
                    onServerSelected = viewModel::selectServer,
                    onRefresh = viewModel::refreshAccount,
                    onSupport = viewModel::openSupport,
                    onFreeProxy = viewModel::openFreeProxyBot,
                    onPrivacyPolicy = viewModel::openPrivacyPolicy,
                    onDeleteAccount = viewModel::openAccountDeletion,
                    onRelinkAccount = viewModel::beginTelegramLogin,
                    onLogout = viewModel::requestLogout,
                    onRoutingPresetSelected = viewModel::setRoutingPreset,
                    onOpenRoutingPreset = { showRoutingPresetDialog = true },
                    onOpenAntiDpi = { showAntiDpiDialog = true },
                    onAntiDpiChanged = viewModel::setAntiDpiEnabled,
                    onAutoHealingChanged = viewModel::setAutoHealingEnabled,
                    onOpenKillSwitch = { showKillSwitchDialog = true },
                    onOpenWifiProtection = { showWifiProtectionDialog = true },
                    onAutomaticServer = viewModel::selectAutomaticServer,
                    onSubscriptionSelected = viewModel::selectSubscription,
                    onSubscriptionShieldChanged = viewModel::setSubscriptionShield,
                    onToggleFavorite = viewModel::toggleFavoriteServer,
                    onSearchQueryChanged = viewModel::setServerSearchQuery,
                    onServerFilterChanged = viewModel::setServerFilter,
                    onRunDiagnostics = viewModel::runDiagnostics,
                    onAnalyzeAppTraffic = {
                        viewModel.loadPerAppTraffic(context.packageManager, context)
                    },
                    onResetPerAppTrafficBaseline = {
                        viewModel.resetPerAppTrafficBaseline(context.packageManager, context)
                    },
                    onClearTrafficHistory = { showClearTrafficHistoryDialog = true },
                    onExportTrafficHistory = { viewModel.exportTrafficHistory(context) },
                    onOpenDevices = { sub ->
                        selectedSubscriptionForDevices = sub
                        showDevicesDialog = true
                    },
                    onOpenLogs = { showLogsDialog = true },
                    onOpenSplitTunneling = {
                        viewModel.loadInstalledApps(context.packageManager)
                        showSplitTunnelDialog = true
                    },
                    onOpenDns = { showDnsDialog = true },
                    onOpenTheme = { showThemeDialog = true },
                    onOpenCustomRouting = { showCustomRoutingDialog = true },
                    onAutoConnectBootChanged = viewModel::setAutoConnectOnBoot,
                    onAutoFallbackChanged = viewModel::setAutoFallbackServer,
                    onAnonymousTelemetryChanged = viewModel::setAnonymousTelemetryEnabled,
                    onShareReferralLink = viewModel::shareReferralLink,
                    onOpenPlans = { openDistributionPlans(viewModel) },
                    onRequestBatteryOptimization = viewModel::requestIgnoreBatteryOptimization,
                    onCheckForUpdates = viewModel::checkForUpdates,
                )
            }
        }
    }

    if (state.showAppDataDisclosure) {
        AlertDialog(
            onDismissRequest = viewModel::declineAppDataDisclosure,
            title = { Text(stringResource(R.string.app_data_disclosure_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.app_data_disclosure_body),
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    TextButton(onClick = viewModel::openPrivacyPolicy) {
                        Text(stringResource(R.string.profile_privacy_policy))
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::acceptAppDataDisclosure) {
                    Text(stringResource(R.string.app_data_disclosure_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::declineAppDataDisclosure) {
                    Text(stringResource(R.string.app_data_disclosure_decline))
                }
            },
        )
    }

    if (state.showVpnDisclosure) {
        AlertDialog(
            onDismissRequest = viewModel::declineVpnDisclosure,
            title = { Text(stringResource(R.string.vpn_disclosure_title)) },
            text = { Text(stringResource(R.string.vpn_disclosure_body)) },
            confirmButton = {
                Button(onClick = viewModel::acceptVpnDisclosure) {
                    Text(stringResource(R.string.vpn_disclosure_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::declineVpnDisclosure) {
                    Text(stringResource(R.string.vpn_disclosure_decline))
                }
            },
        )
    }

    if (state.showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::cancelLogout,
            title = { Text(stringResource(R.string.profile_logout_confirm_title)) },
            text = { Text(stringResource(R.string.profile_logout_confirm_body)) },
            confirmButton = {
                Button(onClick = viewModel::confirmLogout) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelLogout) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRoutingPresetDialog) {
        RoutingPresetDialog(
            currentPreset = state.routingPreset,
            onPresetSelected = {
                viewModel.setRoutingPreset(it)
                showRoutingPresetDialog = false
            },
            onDismiss = { showRoutingPresetDialog = false },
        )
    }

    if (showAntiDpiDialog) {
        AntiDpiDialog(
            enabled = state.antiDpiEnabled,
            currentPreset = state.antiDpiPreset,
            customPackets = state.antiDpiPackets,
            customLength = state.antiDpiLength,
            customInterval = state.antiDpiInterval,
            onPresetSelected = { preset ->
                viewModel.setAntiDpiPreset(preset)
                showAntiDpiDialog = false
            },
            onCustomParamsChanged = { packets, length, interval ->
                viewModel.setAntiDpiCustomParams(packets, length, interval)
                showAntiDpiDialog = false
            },
            onEnabledChanged = { enabled ->
                viewModel.setAntiDpiEnabled(enabled)
                showAntiDpiDialog = false
            },
            onDismiss = { showAntiDpiDialog = false },
        )
    }

    if (showSplitTunnelDialog) {
        SplitTunnelModeDialog(
            currentMode = state.splitTunnelMode,
            selectedCount = state.splitTunnelPackages.size,
            onModeSelected = { mode ->
                viewModel.setSplitTunnelMode(mode)
            },
            onSelectApps = {
                showAppSelectorDialog = true
            },
            onDismiss = { showSplitTunnelDialog = false },
        )
    }

    if (showAppSelectorDialog) {
        AppSelectorDialog(
            apps = state.installedApps,
            selectedPackages = state.splitTunnelPackages,
            onTogglePackage = viewModel::toggleSplitTunnelPackage,
            onDismiss = { showAppSelectorDialog = false },
        )
    }

    if (showDnsDialog) {
        DnsProviderDialog(
            currentProvider = state.dnsProvider,
            customIp = state.customDnsIpv4,
            useDoh = state.useDoh,
            customDohUrl = state.customDohUrl,
            onProviderSelected = viewModel::setDnsProvider,
            onCustomIpChanged = viewModel::setCustomDnsIpv4,
            onUseDohChanged = viewModel::setUseDoh,
            onCustomDohUrlChanged = viewModel::setCustomDohUrl,
            onDismiss = { showDnsDialog = false },
        )
    }

    if (showWifiProtectionDialog) {
        WifiProtectionDialog(
            autoConnect = state.autoConnectUntrustedWifi,
            trustedSsids = state.trustedWifiSsids,
            onAutoConnectChanged = viewModel::setAutoConnectUntrustedWifi,
            onAddTrusted = viewModel::addTrustedWifi,
            onRemoveTrusted = viewModel::removeTrustedWifi,
            onDismiss = { showWifiProtectionDialog = false },
        )
    }

    if (showKillSwitchDialog) {
        KillSwitchDialog(
            enabled = state.killSwitchEnabled,
            onEnabledChanged = viewModel::setKillSwitchEnabled,
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
                showKillSwitchDialog = false
            },
            onDismiss = { showKillSwitchDialog = false },
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            currentTheme = state.themeMode,
            onThemeSelected = viewModel::setThemeMode,
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showCustomRoutingDialog) {
        CustomRoutingDialog(
            directDomains = state.customDirectDomains,
            proxyDomains = state.customProxyDomains,
            onAddDirect = viewModel::addCustomDirectDomain,
            onRemoveDirect = viewModel::removeCustomDirectDomain,
            onAddProxy = viewModel::addCustomProxyDomain,
            onRemoveProxy = viewModel::removeCustomProxyDomain,
            onDismiss = { showCustomRoutingDialog = false },
        )
    }

    if (showLogsDialog) {
        LogsViewerDialog(
            logs = viewModel.getLogs(),
            formattedLogs = viewModel.getFormattedLogs(),
            onClear = viewModel::clearLogs,
            onCopy = { logText ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Levik VPN Logs", logText))
            },
            onSendSupport = viewModel::openSupport,
            onDismiss = { showLogsDialog = false },
        )
    }

    if (showPauseDialog) {
        PauseVpnDialog(
            onDismiss = { showPauseDialog = false },
            onPause = { minutes ->
                viewModel.pauseVpn(minutes)
                showPauseDialog = false
            },
        )
    }

    DistributionUpdateDialog(
        updateState = state.updateState,
        onDownload = viewModel::downloadAndInstallUpdate,
        onDismiss = viewModel::dismissUpdateDialog,
    )

    if (showDevicesDialog && selectedSubscriptionForDevices != null) {
        SubscriptionDevicesDialog(
            subscription = selectedSubscriptionForDevices!!,
            onRevokeDevice = viewModel::revokeDevice,
            onOpenPlans = { openDistributionPlans(viewModel) },
            onDismiss = {
                showDevicesDialog = false
                selectedSubscriptionForDevices = null
            },
        )
    }

    if (showClearTrafficHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearTrafficHistoryDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.traffic_history_clear_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.traffic_history_clear_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearTrafficHistory()
                        showClearTrafficHistoryDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.traffic_history_clear_btn), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearTrafficHistoryDialog = false },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    state.purchaseCatalog?.let { catalog ->
        PurchaseDialog(
            catalog = catalog,
            account = state.account,
            loading = state.purchaseLoading,
            onPurchase = viewModel::purchaseAccess,
            onDismiss = viewModel::closePurchaseFlow,
        )
    }

    state.supportNoteUrl?.let { noteUrl ->
        SupportNoteDialog(
            noteUrl = noteUrl,
            onDismiss = viewModel::clearSupportNoteUrl,
            onCopy = { url ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Levik VPN Support Note", url))
            },
            onOpenSupport = viewModel::openSupport,
        )
    }

    if (state.runningDiagnostics || state.diagnosticReport != null) {
        DiagnosticsDialog(
            running = state.runningDiagnostics,
            report = state.diagnosticReport,
            isSharingNote = state.isSharingNote,
            onCopy = { reportText ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Levik VPN Report", reportText))
            },
            onSendSupport = viewModel::openSupport,
            onShareSupportNote = viewModel::shareDiagnosticReportAsNote,
            onDismiss = viewModel::dismissDiagnostics,
        )
    }
}

@Composable
private fun PurchaseDialog(
    catalog: com.leviknet.vpn.core.network.CatalogResponse,
    account: MobileAccountResponse?,
    loading: Boolean,
    onPurchase: (String, String?, String?, Int?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val availableTariffs = catalog.tariffs.filter { it.purchaseEnabled && it.periods.isNotEmpty() }
    val choices = buildList {
        add(PurchaseChoice("access_purchase", null, stringResource(R.string.purchase_new_access)))
        account?.subscriptions.orEmpty().forEach { subscription ->
            if (subscription.actions.renew) {
                add(PurchaseChoice("access_renewal", subscription.uuid, stringResource(R.string.purchase_renew, subscription.title)))
            }
            if (subscription.actions.slotAddon) {
                add(PurchaseChoice("slot_addon", subscription.uuid, stringResource(R.string.purchase_slot_addon, subscription.title)))
            }
            if (subscription.actions.trafficAddon) {
                add(PurchaseChoice("traffic_addon", subscription.uuid, stringResource(R.string.purchase_traffic_addon, subscription.title)))
            }
        }
    }
    var choiceKey by remember(catalog, account) { mutableStateOf("access_purchase:") }
    val choice = choices.firstOrNull { "${it.kind}:${it.subscriptionId.orEmpty()}" == choiceKey }
        ?: choices.first()
    val renewalTariffId = account?.subscriptions
        ?.firstOrNull { it.uuid == choice.subscriptionId }
        ?.tariffId
    var tariffId by remember(catalog) { mutableStateOf(availableTariffs.firstOrNull()?.id.orEmpty()) }
    val effectiveTariffId = if (choice.kind == "access_renewal") renewalTariffId.orEmpty() else tariffId
    val selectedTariff = availableTariffs.firstOrNull { it.id == effectiveTariffId }
    var months by remember(effectiveTariffId) { mutableStateOf(selectedTariff?.periods?.firstOrNull()?.months ?: 0) }
    var paymentMethodId by remember(catalog) {
        mutableStateOf(catalog.paymentMethods.firstOrNull()?.id.orEmpty())
    }
    val accessOrder = choice.kind in setOf("access_purchase", "access_renewal")
    val canPurchase = (!accessOrder || (effectiveTariffId.isNotBlank() && months > 0)) &&
        paymentMethodId.isNotBlank() && !loading

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.purchase_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                choices.forEach { item ->
                    val itemKey = "${item.kind}:${item.subscriptionId.orEmpty()}"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = itemKey == choiceKey,
                                onClick = { choiceKey = itemKey },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = itemKey == choiceKey, onClick = null)
                        Text(item.label)
                    }
                }
                if (choice.kind == "access_purchase") availableTariffs.forEach { tariff ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = tariff.id == tariffId,
                                onClick = { tariffId = tariff.id },
                                role = Role.RadioButton,
                            ),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (tariff.id == tariffId) LevikBlue else MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = tariff.id == tariffId, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(tariff.title, fontWeight = FontWeight.SemiBold)
                                Text(tariff.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (accessOrder) {
                    Text(stringResource(R.string.purchase_period), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        selectedTariff?.periods.orEmpty().forEach { period ->
                            FilterChip(
                                selected = months == period.months,
                                onClick = { months = period.months },
                                label = { Text("${period.title} · ${period.amountRub} ₽") },
                            )
                        }
                    }
                } else {
                    catalog.addons.firstOrNull { it.id == choice.kind }?.let { addon ->
                        Text(
                            text = "${addon.title} · ${addon.amountRub} ₽",
                            fontWeight = FontWeight.SemiBold,
                            color = LevikBlue,
                        )
                    }
                }
                Text(stringResource(R.string.purchase_payment_method), fontWeight = FontWeight.SemiBold)
                catalog.paymentMethods.forEach { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = method.id == paymentMethodId,
                                onClick = { paymentMethodId = method.id },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = method.id == paymentMethodId, onClick = null)
                        Text(method.title)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onPurchase(
                        choice.kind,
                        choice.subscriptionId,
                        effectiveTariffId.takeIf { accessOrder },
                        months.takeIf { accessOrder },
                        paymentMethodId,
                    )
                },
                enabled = canPurchase,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.purchase_continue))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private data class PurchaseChoice(
    val kind: String,
    val subscriptionId: String?,
    val label: String,
)

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoginScreen(
    login: LoginUiState,
    snackbarHostState: SnackbarHostState,
    onTelegramLogin: () -> Unit,
    onWebsiteLogin: () -> Unit,
    onDeviceTrial: () -> Unit,
    onLteTrial: () -> Unit,
    onFreeProxy: () -> Unit,
    onOpenAgain: () -> Unit,
    onRetry: () -> Unit,
    onPrivacyPolicy: () -> Unit,
) {
    var showTrialChoice by remember { mutableStateOf(false) }
    val waitingForWebsite =
        (login as? LoginUiState.Waiting)?.authorization is ChallengeAuthorization.AccountActivation

    if (showTrialChoice) {
        AlertDialog(
            onDismissRequest = { showTrialChoice = false },
            title = { Text(stringResource(R.string.trial_choice_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.trial_choice_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            showTrialChoice = false
                            onDeviceTrial()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.trial_regular_title),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.trial_regular_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            showTrialChoice = false
                            onLteTrial()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_speed),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.trial_lte_title),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.trial_lte_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTrialChoice = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(46.dp),
                        tint = LevikBlue,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            BrandTitle(isConnected = false)
            Spacer(Modifier.height(20.dp))
            Text(
                text = when (login) {
                    is LoginUiState.Waiting -> stringResource(
                        if (waitingForWebsite) {
                            R.string.login_waiting_website
                        } else {
                            R.string.login_waiting_telegram
                        },
                    )
                    LoginUiState.Expired -> stringResource(R.string.login_expired)
                    else -> stringResource(R.string.login_title)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (login is LoginUiState.Waiting) {
                    stringResource(
                        if (waitingForWebsite) {
                            R.string.login_waiting_website_description
                        } else {
                            R.string.login_waiting_telegram_description
                        },
                    )
                } else {
                    stringResource(R.string.login_description)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
            if (login is LoginUiState.Waiting) {
                Spacer(Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                        text = login.authorization.code,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = LevikBlue,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            when (login) {
                LoginUiState.Loading -> CircularProgressIndicator()
                is LoginUiState.Waiting -> OutlinedButton(
                    onClick = onOpenAgain,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        stringResource(
                            if (waitingForWebsite) {
                                R.string.login_open_website_again
                            } else {
                                R.string.login_open_telegram_again
                            },
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LoginUiState.Expired -> Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.login_retry), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                LoginUiState.Idle -> {
                    Button(
                        onClick = { showTrialChoice = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LevikDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.login_try_free),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onTelegramLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LevikDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_telegram),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.login_confirm_telegram),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.login_telegram_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onWebsiteLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LevikDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_web),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.login_website),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onFreeProxy) {
                Icon(
                    painter = painterResource(R.drawable.ic_servers),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.free_proxy_button))
            }
            TextButton(
                onClick = onPrivacyPolicy,
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_privacy),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profile_privacy_policy), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MainContent(
    state: AppUiState,
    snackbarHostState: SnackbarHostState,
    onTabSelected: (AppTab) -> Unit,
    onConnect: () -> Unit,
    onTrial: () -> Unit,
    onOpenPauseVpn: () -> Unit,
    onResumeVpn: () -> Unit,
    onServerSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onSupport: () -> Unit,
    onFreeProxy: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onDeleteAccount: () -> Unit,
    onRelinkAccount: () -> Unit,
    onLogout: () -> Unit,
    onRoutingPresetSelected: (RoutingPreset) -> Unit,
    onOpenRoutingPreset: () -> Unit,
    onOpenAntiDpi: () -> Unit,
    onAntiDpiChanged: (Boolean) -> Unit,
    onAutoHealingChanged: (Boolean) -> Unit,
    onOpenKillSwitch: () -> Unit,
    onOpenWifiProtection: () -> Unit,
    onAutomaticServer: () -> Unit,
    onSubscriptionSelected: (String) -> Unit,
    onSubscriptionShieldChanged: (String, Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onServerFilterChanged: (ServerFilterType) -> Unit,
    onRunDiagnostics: () -> Unit,
    onAnalyzeAppTraffic: () -> Unit,
    onResetPerAppTrafficBaseline: () -> Unit,
    onClearTrafficHistory: () -> Unit,
    onExportTrafficHistory: () -> Unit,
    onOpenDevices: (SubscriptionSummary) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSplitTunneling: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenCustomRouting: () -> Unit,
    onAutoConnectBootChanged: (Boolean) -> Unit,
    onAutoFallbackChanged: (Boolean) -> Unit,
    onAnonymousTelemetryChanged: (Boolean) -> Unit,
    onShareReferralLink: (String) -> Unit,
    onOpenPlans: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AppNavigationBar(
                selected = state.tab,
                onSelected = onTabSelected,
            )
        },
    ) { padding ->
        when (state.tab) {
            AppTab.HOME -> HomeScreen(
                modifier = Modifier.padding(padding),
                state = state,
                onConnect = onConnect,
                onTrial = onTrial,
                onOpenPauseVpn = onOpenPauseVpn,
                onResumeVpn = onResumeVpn,
                onProfile = { onTabSelected(AppTab.PROFILE) },
                onServers = { onTabSelected(AppTab.SERVERS) },
                onOpenRoutingPreset = onOpenRoutingPreset,
                onOpenAntiDpi = onOpenAntiDpi,
            )
            AppTab.SERVERS -> ServersScreen(
                modifier = Modifier.padding(padding),
                profile = state.profile,
                selectedServerId = state.selectedServerId,
                connectionState = state.vpn.state,
                loading = state.refreshing,
                onServerSelected = onServerSelected,
                automaticServer = state.automaticServer,
                onAutomaticServer = onAutomaticServer,
                serverPings = state.serverPings,
                pingingServers = state.pingingServers,
                favoriteServerIds = state.favoriteServerIds,
                onToggleFavorite = onToggleFavorite,
                searchQuery = state.serverSearchQuery,
                onSearchQueryChanged = onSearchQueryChanged,
                filterType = state.serverFilter,
                onFilterChanged = onServerFilterChanged,
                levikStatus = state.levikStatus,
            )
            AppTab.STATS -> StatsScreen(
                modifier = Modifier.padding(padding),
                vpn = state.vpn,
                liveSpeedHistory = state.liveSpeedHistory,
                trafficHistory = state.trafficHistory,
                perAppTraffic = state.perAppTraffic,
                onRunDiagnostics = onRunDiagnostics,
                onAnalyzeAppTraffic = onAnalyzeAppTraffic,
                onResetPerAppTrafficBaseline = onResetPerAppTrafficBaseline,
                onClearTrafficHistory = onClearTrafficHistory,
                onExportTrafficHistory = onExportTrafficHistory,
            )
            AppTab.PROFILE -> ProfileScreen(
                modifier = Modifier.padding(padding),
                account = state.account,
                profile = state.profile,
                session = state.session,
                login = state.login,
                loading = state.refreshing,
                onRefresh = onRefresh,
                onSupport = onSupport,
                onFreeProxy = onFreeProxy,
                onPrivacyPolicy = onPrivacyPolicy,
                onDeleteAccount = onDeleteAccount,
                onRelinkAccount = onRelinkAccount,
                onLogout = onLogout,
                routingPreset = state.routingPreset,
                onOpenRoutingPreset = onOpenRoutingPreset,
                antiDpiPreset = state.antiDpiPreset,
                antiDpiEnabled = state.antiDpiEnabled,
                onOpenAntiDpi = onOpenAntiDpi,
                onAntiDpiChanged = onAntiDpiChanged,
                autoHealingEnabled = state.autoHealingEnabled,
                onAutoHealingChanged = onAutoHealingChanged,
                onOpenKillSwitch = onOpenKillSwitch,
                onOpenWifiProtection = onOpenWifiProtection,
                selectedSubscriptionId = state.selectedSubscriptionId,
                subscriptionSelectionEnabled = state.vpn.state in setOf(
                    VpnConnectionState.DISCONNECTED,
                    VpnConnectionState.ERROR,
                ),
                onSubscriptionSelected = onSubscriptionSelected,
                onSubscriptionShieldChanged = onSubscriptionShieldChanged,
                onOpenDevices = onOpenDevices,
                splitTunnelMode = state.splitTunnelMode,
                splitTunnelSelectedCount = state.splitTunnelPackages.size,
                onOpenSplitTunneling = onOpenSplitTunneling,
                dnsProvider = state.dnsProvider,
                useDoh = state.useDoh,
                onOpenDns = onOpenDns,
                themeMode = state.themeMode,
                onOpenTheme = onOpenTheme,
                onOpenCustomRouting = onOpenCustomRouting,
                customDirectCount = state.customDirectDomains.size,
                customProxyCount = state.customProxyDomains.size,
                autoConnectOnBoot = state.autoConnectOnBoot,
                onAutoConnectBootChanged = onAutoConnectBootChanged,
                autoFallbackServer = state.autoFallbackServer,
                onAutoFallbackChanged = onAutoFallbackChanged,
                anonymousTelemetryEnabled = state.anonymousTelemetryEnabled,
                onAnonymousTelemetryChanged = onAnonymousTelemetryChanged,
                onShareReferralLink = onShareReferralLink,
                onOpenPlans = onOpenPlans,
                onRequestBatteryOptimization = onRequestBatteryOptimization,
                onCheckForUpdates = onCheckForUpdates,
                onOpenLogs = onOpenLogs,
            )
        }
    }
}

@Composable
private fun AppNavigationBar(
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(LevikDimensions.CardRadius),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline,
            ),
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                modifier = Modifier.height(72.dp),
            ) {
                NavigationDestination.entries.forEach { destination ->
                    val isSelected = selected == destination.tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onSelected(destination.tab) },
                        icon = {
                            Icon(
                                painter = painterResource(destination.icon),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(destination.label),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LevikBlue,
                            selectedTextColor = LevikBlue,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    state: AppUiState,
    onConnect: () -> Unit,
    onTrial: () -> Unit,
    onOpenPauseVpn: () -> Unit,
    onResumeVpn: () -> Unit,
    onProfile: () -> Unit,
    onServers: () -> Unit,
    onOpenRoutingPreset: () -> Unit,
    onOpenAntiDpi: () -> Unit,
) {
    val selectedServer = state.profile?.servers?.firstOrNull {
        it.id == state.selectedServerId
    }
    val isConnected = state.vpn.state == VpnConnectionState.CONNECTED
    val account = state.account
    val trialAvailable = account?.trial?.eligible == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandTitle(isConnected = isConnected)
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onProfile,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_crown),
                        contentDescription = stringResource(R.string.content_plan),
                        modifier = Modifier.size(24.dp),
                        tint = LevikBlue,
                    )
                }
            }
        }

        // Quick feature badges row
        Spacer(Modifier.height(18.dp))
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                onClick = onOpenRoutingPreset,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (state.routingPreset) {
                            RoutingPreset.GLOBAL -> "Global"
                            RoutingPreset.BYPASS_RU -> "Обход РФ"
                            RoutingPreset.BLOCKED_ONLY -> "Anti-Block"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (state.useDoh) {
                Surface(
                    onClick = onProfile,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_servers),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildAnnotatedString {
                                append("DoH: ")
                                withStyle(SpanStyle(color = if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A))) { append("ON") }
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (state.antiDpiEnabled) {
                Surface(
                    onClick = onOpenAntiDpi,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_anti_dpi),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = LevikBlue,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (state.antiDpiPreset) {
                                AntiDpiPreset.TLS_HELLO -> "Anti-DPI: TLS Hello"
                                AntiDpiPreset.MICRO -> "Anti-DPI: Micro"
                                AntiDpiPreset.BALANCED -> "Anti-DPI: Balanced"
                                AntiDpiPreset.DEEP -> "Anti-DPI: Deep"
                                AntiDpiPreset.CUSTOM -> "Anti-DPI: Custom"
                                AntiDpiPreset.OFF -> "Anti-DPI"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (trialAvailable) {
            Spacer(Modifier.height(22.dp))
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.trial_mobile_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.trial_mobile_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onTrial,
                        enabled = !state.refreshing && state.login !is LoginUiState.Loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LevikDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.trial_mobile_activate),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Central Power Button with Glowing Ring and Status
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PowerButton(
                vpn = state.vpn,
                busy = state.refreshing,
                onClick = onConnect,
            )
        }

        // Action Button (Pause / Resume)
        if (state.vpn.state == VpnConnectionState.PAUSED) {
            Spacer(Modifier.height(22.dp))
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val formatted = String.format(
                        java.util.Locale.US,
                        "%02d:%02d",
                        state.vpn.pausedRemainingSeconds / 60,
                        state.vpn.pausedRemainingSeconds % 60,
                    )
                    Text(
                        text = stringResource(R.string.status_paused, formatted),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pause_vpn_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onResumeVpn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LevikDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) LevikGreen else Color(0xFF16A34A),
                        ),
                    ) {
                        Text(
                            stringResource(R.string.resume_vpn_btn),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        } else if (state.vpn.state == VpnConnectionState.CONNECTED) {
            Spacer(Modifier.height(22.dp))
            Surface(
                onClick = onOpenPauseVpn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LevikDimensions.ButtonHeight),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = null,
                        tint = LevikBlue,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.pause_vpn_btn),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // Server Summary and Traffic Card
        Spacer(Modifier.height(24.dp))
        ServerSummaryCard(
            server = selectedServer,
            vpn = state.vpn,
            lteTraffic = state.lteTraffic,
            pingMs = state.pingMs,
            automaticServer = state.automaticServer,
            onClick = onServers,
        )

        state.vpn.failure?.let { failure ->
            Spacer(Modifier.height(14.dp))
            Text(
                text = failure.localized(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            state.vpn.failureDetail?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.failure_detail_label, detail),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BrandTitle(isConnected: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Levik",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = (-0.5).sp,
        )
        Text(
            text = " VPN",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            letterSpacing = (-0.5).sp,
        )
    }
}

@Composable
private fun SignalBarsIndicator(
    pingMs: Long?,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bars, color) = when {
        pingMs == null -> 0 to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        pingMs < 70 -> 4 to (if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A))
        pingMs < 140 -> 3 to (if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A))
        pingMs < 250 -> 2 to (if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706))
        else -> 1 to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val heights = listOf(6.dp, 10.dp, 14.dp, 18.dp)
        heights.forEachIndexed { index, height ->
            val isBarActive = index < bars
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (isBarActive) color
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
            )
        }
    }
}

@Composable
private fun PowerButton(
    vpn: VpnSnapshot,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val isConnected = vpn.state == VpnConnectionState.CONNECTED
    val isTransitioning = busy || vpn.state in setOf(
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING,
        VpnConnectionState.STOPPING,
    )
    val isPaused = vpn.state == VpnConnectionState.PAUSED
    val isError = vpn.state in setOf(VpnConnectionState.ERROR, VpnConnectionState.LOCKDOWN)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val localizedState = vpn.state.localized()
    val primaryAccent = when {
        isConnected -> if (isDark) LevikBrightBlue else LevikBlue
        isPaused -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
        isError -> MaterialTheme.colorScheme.error
        isTransitioning -> LevikBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    val glowAlpha by animateFloatAsState(
        targetValue = if (isConnected) (if (isDark) 0.35f else 0.16f) else if (isTransitioning) (if (isDark) 0.2f else 0.10f) else 0.04f,
        animationSpec = tween(500),
        label = "glowAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Ambient outer glow
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = glowAlpha),
                            primaryAccent.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }

            // Outer circular ring with neon/vibrant stroke
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) {
                            if (isDark) Color(0xFF0C162A) else Color(0xFFEFF6FF)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                    .border(
                        width = if (isConnected) 2.5.dp else 1.5.dp,
                        brush = if (isConnected) {
                            if (isDark) {
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF38BDF8),
                                        Color(0xFF22D3EE),
                                        Color(0xFF3B82F6),
                                        Color(0xFF38BDF8),
                                    )
                                )
                            } else {
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF2563EB),
                                        Color(0xFF06B6D4),
                                        Color(0xFF3B82F6),
                                        Color(0xFF2563EB),
                                    )
                                )
                            }
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.outline,
                                    MaterialTheme.colorScheme.outlineVariant,
                                )
                            )
                        },
                        shape = CircleShape,
                    )
                    .clickable(
                        enabled = !isTransitioning,
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .semantics {
                        role = Role.Button
                        stateDescription = localizedState
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Inner button surface
                Surface(
                    modifier = Modifier.size(150.dp),
                    shape = CircleShape,
                    color = if (isConnected) {
                        if (isDark) Color(0xFF0F172A) else Color.White
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        if (isConnected && !isDark) 1.5.dp else 1.dp,
                        if (isConnected) {
                            if (isDark) Color(0xFF1E293B) else Color(0xFFBFDBFE)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        },
                    ),
                    shadowElevation = if (!isDark && isConnected) 2.dp else 0.dp,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isTransitioning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(54.dp),
                                color = LevikBlue,
                                strokeWidth = 4.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_power),
                                contentDescription = stringResource(R.string.content_power_button),
                                modifier = Modifier.size(54.dp),
                                tint = if (isConnected) {
                                    if (isDark) Color(0xFF38BDF8) else Color(0xFF2563EB)
                                } else {
                                    primaryAccent
                                },
                            )
                        }
                    }
                }
            }

            // Shield check badge at bottom center of the circular border
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2563EB))
                        .border(2.dp, if (isDark) Color(0xFF0B0F19) else Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield_check),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Status title & subtitle
        val statusTitle = when {
            isConnected -> stringResource(R.string.status_connected)
            isTransitioning -> stringResource(R.string.status_connecting)
            isPaused -> stringResource(R.string.status_paused, "")
            isError -> stringResource(R.string.status_error)
            else -> stringResource(R.string.status_disconnected)
        }
        val statusTitleColor = when {
            isConnected -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
            isPaused -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
            isError -> MaterialTheme.colorScheme.error
            isTransitioning -> LevikBlue
            else -> MaterialTheme.colorScheme.onSurface
        }
        val statusSubtitle = when {
            isConnected -> stringResource(R.string.status_connected_desc)
            isTransitioning -> stringResource(R.string.status_connecting_desc)
            isPaused -> stringResource(R.string.pause_vpn_desc)
            isError -> stringResource(R.string.status_error_desc)
            else -> stringResource(R.string.status_disconnected_desc)
        }

        Text(
            text = statusTitle,
            color = statusTitleColor,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = statusSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ServerSummaryCard(
    server: TunnelServer?,
    vpn: VpnSnapshot,
    lteTraffic: TrafficSummary?,
    pingMs: Long?,
    automaticServer: Boolean,
    onClick: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            val flagDescriptionText = flagDescription(server?.countryCode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = flagEmoji(server?.countryCode),
                    fontSize = 32.sp,
                    modifier = Modifier.semantics {
                        contentDescription = flagDescriptionText
                    },
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            automaticServer -> stringResource(R.string.selected_server_automatic)
                            server?.isMobileServer() == true -> stringResource(R.string.selected_server_mobile)
                            else -> stringResource(R.string.selected_server_regular)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (server?.isMobileServer() == true && !automaticServer) {
                            if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server?.name?.displayName() ?: stringResource(R.string.select_server),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!automaticServer && server?.isMobileServer() == true) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = (if (isDark) Color(0xFFF59E0B) else Color(0xFFD97706)).copy(alpha = 0.15f),
                            ) {
                                Text(
                                    text = stringResource(R.string.server_badge_mobile),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_down),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(54.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(14.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.ping),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pingMs?.let { stringResource(R.string.ping_ms, it.toInt()) }
                                ?: stringResource(R.string.not_available),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = pingMs?.let { ping ->
                                when {
                                    ping < 100 -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
                                    ping < 250 -> if (isDark) Color(0xFF38BDF8) else Color(0xFF2563EB)
                                    else -> MaterialTheme.colorScheme.error
                                }
                            } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        SignalBarsIndicator(pingMs = pingMs)
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outline,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_usage),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = stringResource(
                                if (lteTraffic == null) {
                                    R.string.total_data_usage
                                } else {
                                    R.string.lte_traffic_usage
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = if (lteTraffic == null) {
                            dataUsageValue(vpn.downloadedBytes + vpn.uploadedBytes)
                        } else {
                            trafficLimitValue(lteTraffic.usedBytes, lteTraffic.limitBytes)
                        },
                    )
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(58.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1.2f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_speed),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = stringResource(R.string.speed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = speedValue(
                            vpn.downloadBytesPerSecond,
                            vpn.uploadBytesPerSecond,
                        ),
                    )
                }
            }
            if (lteTraffic != null) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = {
                        (lteTraffic.usedBytes.toDouble() / lteTraffic.limitBytes.toDouble())
                            .coerceIn(0.0, 1.0)
                            .toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

@Composable
private fun dataUsageValue(bytes: Long): AnnotatedString {
    val formatted = formatBytes(bytes)
    val separator = formatted.lastIndexOf(' ')
    if (separator <= 0) return AnnotatedString(formatted)
    val numberStyle = SpanStyle(
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val unitStyle = SpanStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return buildAnnotatedString {
        withStyle(numberStyle) {
            append(formatted.substring(0, separator))
        }
        append(" ")
        withStyle(unitStyle) {
            append(formatted.substring(separator + 1))
        }
    }
}

@Composable
private fun trafficLimitValue(usedBytes: Long, limitBytes: Long): AnnotatedString {
    val valueStyle = SpanStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val separatorStyle = SpanStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return buildAnnotatedString {
        withStyle(valueStyle) { append(formatBytes(usedBytes)) }
        withStyle(separatorStyle) { append(" / ") }
        withStyle(valueStyle) { append(formatBytes(limitBytes)) }
    }
}

@Composable
private fun speedValue(downloadBytesPerSecond: Long, uploadBytesPerSecond: Long): AnnotatedString {
    val useMegabits = maxOf(downloadBytesPerSecond, uploadBytesPerSecond) * 8.0 >= 1_000_000
    val unit = if (useMegabits) {
        stringResource(R.string.rate_unit_mbps)
    } else {
        stringResource(R.string.rate_unit_kbps)
    }
    val divisor = if (useMegabits) 1_000_000.0 else 1_000.0
    val numberStyle = speedNumberStyle()
    val symbolStyle = speedSymbolStyle()
    return buildAnnotatedString {
        withStyle(numberStyle) {
            append(formatRateNumber(downloadBytesPerSecond * 8.0 / divisor))
        }
        withStyle(symbolStyle) { append(" ↓ / ") }
        withStyle(numberStyle) {
            append(formatRateNumber(uploadBytesPerSecond * 8.0 / divisor))
        }
        withStyle(symbolStyle) { append(" ↑ ") }
        withStyle(symbolStyle) { append(unit) }
    }
}

@Composable
private fun speedNumberStyle() = SpanStyle(
    fontSize = 21.sp,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun speedSymbolStyle() = SpanStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

private fun formatRateNumber(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 9.95 -> "%.0f".format(Locale.US, bitsPerSecond)
    bitsPerSecond >= 0.05 -> "%.1f".format(Locale.US, bitsPerSecond)
    else -> "0"
}

private fun flagEmoji(countryCode: String?): String {
    val code = countryCode?.trim()?.uppercase(Locale.US).orEmpty()
    if (code.length != 2 || !code.all { it in 'A'..'Z' } || code == "XX") return "🌐"
    val codePoints = code.map { 0x1F1E6 + (it - 'A') }.toIntArray()
    return String(codePoints, 0, codePoints.size)
}

private fun String.displayName(): String = removePrefix("🚀").trimStart().ifBlank { this }

@Composable
private fun flagDescription(countryCode: String?): String =
    stringResource(R.string.content_server_code, countryCode ?: "XX")

@Composable
private fun ServersScreen(
    modifier: Modifier,
    profile: PreparedTunnelProfile?,
    selectedServerId: String?,
    connectionState: VpnConnectionState,
    loading: Boolean,
    onServerSelected: (String) -> Unit,
    automaticServer: Boolean,
    onAutomaticServer: () -> Unit,
    serverPings: Map<String, Long?>,
    pingingServers: Boolean,
    favoriteServerIds: Set<String>,
    onToggleFavorite: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    filterType: ServerFilterType,
    onFilterChanged: (ServerFilterType) -> Unit,
    levikStatus: LevikStatusSnapshot? = null,
) {
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.servers_title),
            subtitle = stringResource(R.string.servers_description),
        )

        levikStatus?.let { status ->
            val incidents = status.servers.count { it.state != "online" }
            val online = status.servers.count { it.state == "online" }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (incidents == 0) {
                    LevikGreen.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (incidents == 0) LevikGreen.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(
                            if (incidents == 0) R.drawable.ic_shield else R.drawable.ic_anti_dpi,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (incidents == 0) LevikGreen else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (incidents == 0) {
                                stringResource(R.string.levik_status_operational)
                            } else {
                                stringResource(R.string.levik_status_incidents, incidents)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.levik_status_servers,
                                online,
                                status.servers.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Search and Filter Bar
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_servers)) },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_servers),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    ServerFilterType.ALL to stringResource(R.string.filter_all),
                    ServerFilterType.REGULAR to stringResource(R.string.filter_regular),
                    ServerFilterType.MOBILE to stringResource(R.string.filter_mobile),
                    ServerFilterType.FAVORITES to stringResource(R.string.filter_favorites),
                    ServerFilterType.FASTEST to stringResource(R.string.filter_fastest),
                ).forEach { (type, label) ->
                    val isSelected = filterType == type
                    Surface(
                        onClick = { onFilterChanged(type) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) LevikBlue else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) LevikBlue else MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            profile == null || profile.servers.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.servers_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val query = searchQuery.trim().lowercase()
                var filtered = profile.servers.filter { server ->
                    val matchesQuery = query.isEmpty() ||
                        server.name.lowercase().contains(query) ||
                        server.countryCode.lowercase().contains(query)
                    val matchesFilter = when (filterType) {
                        ServerFilterType.ALL -> true
                        ServerFilterType.REGULAR -> !server.isMobileServer()
                        ServerFilterType.MOBILE -> server.isMobileServer()
                        ServerFilterType.FAVORITES -> favoriteServerIds.contains(server.id)
                        ServerFilterType.FASTEST -> true
                    }
                    matchesQuery && matchesFilter
                }

                if (filterType == ServerFilterType.FASTEST) {
                    filtered = filtered.sortedBy { serverPings[it.id] ?: Long.MAX_VALUE }
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (filterType == ServerFilterType.ALL && searchQuery.isEmpty()) {
                        item(key = "automatic") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = automaticServer,
                                        role = Role.RadioButton,
                                        onClick = onAutomaticServer,
                                    ),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (automaticServer) 1.5.dp else 1.dp,
                                    if (automaticServer) LevikBlue else MaterialTheme.colorScheme.outline,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = LevikBlue.copy(alpha = 0.15f),
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_stats),
                                                contentDescription = null,
                                                tint = LevikBlue,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.server_automatic),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = if (pingingServers) {
                                                stringResource(R.string.server_ping_checking)
                                             } else {
                                                stringResource(R.string.server_automatic_description)
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    RadioButton(
                                        selected = automaticServer,
                                        onClick = null,
                                    )
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (filterType == ServerFilterType.FAVORITES) {
                                        stringResource(R.string.no_favorite_servers)
                                    } else {
                                        stringResource(R.string.servers_empty)
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else if (filterType == ServerFilterType.ALL && searchQuery.isEmpty()) {
                        val regularServers = filtered.filter { !it.isMobileServer() }
                        val mobileServers = filtered.filter { it.isMobileServer() }

                        if (regularServers.isNotEmpty() && mobileServers.isNotEmpty()) {
                            item(key = "section_regular_header") {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 2.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.servers_category_regular),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(R.string.servers_category_regular_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(regularServers, key = TunnelServer::id) { server ->
                                ServerItemCard(
                                    server = server,
                                    selected = !automaticServer && server.id == selectedServerId,
                                    isFav = favoriteServerIds.contains(server.id),
                                    pingValue = serverPings[server.id],
                                    pingingServers = pingingServers,
                                    isDark = isDark,
                                    onServerSelected = onServerSelected,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                            item(key = "section_mobile_header") {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp, bottom = 2.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(R.string.servers_category_mobile),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = (if (isDark) Color(0xFFF59E0B) else Color(0xFFD97706)).copy(alpha = 0.15f),
                                        ) {
                                            Text(
                                                text = stringResource(R.string.server_badge_mobile),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.servers_category_mobile_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(mobileServers, key = TunnelServer::id) { server ->
                                ServerItemCard(
                                    server = server,
                                    selected = !automaticServer && server.id == selectedServerId,
                                    isFav = favoriteServerIds.contains(server.id),
                                    pingValue = serverPings[server.id],
                                    pingingServers = pingingServers,
                                    isDark = isDark,
                                    onServerSelected = onServerSelected,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                        } else {
                            items(filtered, key = TunnelServer::id) { server ->
                                ServerItemCard(
                                    server = server,
                                    selected = !automaticServer && server.id == selectedServerId,
                                    isFav = favoriteServerIds.contains(server.id),
                                    pingValue = serverPings[server.id],
                                    pingingServers = pingingServers,
                                    isDark = isDark,
                                    onServerSelected = onServerSelected,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                        }
                    } else {
                        items(filtered, key = TunnelServer::id) { server ->
                            ServerItemCard(
                                server = server,
                                selected = !automaticServer && server.id == selectedServerId,
                                isFav = favoriteServerIds.contains(server.id),
                                pingValue = serverPings[server.id],
                                pingingServers = pingingServers,
                                isDark = isDark,
                                onServerSelected = onServerSelected,
                                onToggleFavorite = onToggleFavorite,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerItemCard(
    server: TunnelServer,
    selected: Boolean,
    isFav: Boolean,
    pingValue: Long?,
    pingingServers: Boolean,
    isDark: Boolean,
    onServerSelected: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val flagDescriptionText = flagDescription(server.countryCode)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onServerSelected(server.id) },
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) LevikBlue else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = flagEmoji(server.countryCode),
                    fontSize = 28.sp,
                    modifier = Modifier.semantics {
                        contentDescription = flagDescriptionText
                    },
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name.displayName(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (server.isMobileServer()) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = (if (isDark) Color(0xFFF59E0B) else Color(0xFFD97706)).copy(alpha = 0.15f),
                            ) {
                                Text(
                                    text = stringResource(R.string.server_badge_mobile),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                                )
                            }
                        }
                    }
                }
                IconButton(
                    onClick = { onToggleFavorite(server.id) },
                    modifier = Modifier.size(LevikDimensions.IconButtonSize),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isFav) R.drawable.ic_crown else R.drawable.ic_shield,
                        ),
                        contentDescription = stringResource(R.string.favorite_toggle),
                        tint = if (isFav) LevikBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                RadioButton(selected = selected, onClick = null)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        pingValue == null && pingingServers ->
                            stringResource(R.string.server_ping_checking_short)
                        pingValue != null -> stringResource(
                            R.string.ping_ms,
                            pingValue.toInt(),
                        )
                        else -> stringResource(R.string.not_available)
                    },
                    color = pingValue?.let { ping ->
                        when {
                            ping < 100 -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
                            ping < 250 -> if (isDark) Color(0xFF38BDF8) else Color(0xFF2563EB)
                            else -> MaterialTheme.colorScheme.error
                        }
                    } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                SignalBarsIndicator(pingMs = pingValue)
            }
        }
    }
}

@Composable
private fun StatsScreen(
    modifier: Modifier,
    vpn: VpnSnapshot,
    liveSpeedHistory: List<SpeedSample>,
    trafficHistory: List<DailyTraffic>,
    perAppTraffic: List<AppTrafficUsage>,
    onRunDiagnostics: () -> Unit,
    onAnalyzeAppTraffic: () -> Unit,
    onResetPerAppTrafficBaseline: () -> Unit,
    onClearTrafficHistory: () -> Unit,
    onExportTrafficHistory: () -> Unit,
) {
    var historyDaysMode by remember { mutableStateOf(7) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = stringResource(R.string.stats_title))
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Live Real-Time Speed Graph
            Text(
                text = stringResource(R.string.live_traffic_chart_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            LiveSpeedChartCard(
                history = liveSpeedHistory,
                currentDown = vpn.downloadBytesPerSecond,
                currentUp = vpn.uploadBytesPerSecond,
            )

            Text(
                text = stringResource(R.string.stats_session),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            StatsCard(
                icon = painterResource(R.drawable.ic_stats),
                label = stringResource(R.string.stats_downloaded),
                value = formatBytes(vpn.downloadedBytes),
            )
            StatsCard(
                icon = painterResource(R.drawable.ic_servers),
                label = stringResource(R.string.stats_uploaded),
                value = formatBytes(vpn.uploadedBytes),
            )
            StatsCard(
                icon = painterResource(R.drawable.ic_home),
                label = stringResource(R.string.stats_duration),
                value = formatDuration(vpn.connectedDurationSeconds),
            )

            // Per-App Network Activity Breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.per_app_traffic_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.per_app_traffic_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (perAppTraffic.isNotEmpty()) {
                    TextButton(
                        onClick = onResetPerAppTrafficBaseline,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.per_app_reset_baseline_btn),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (perAppTraffic.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.per_app_traffic_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Justify,
                            )
                            Button(
                                onClick = onAnalyzeAppTraffic,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(LevikDimensions.ButtonHeight),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(stringResource(R.string.per_app_load_btn), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        perAppTraffic.take(8).forEach { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon.toBitmap(32, 32).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shield),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = LevikBlue,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = formatBytes(app.rxBytes + app.txBytes),
                                    fontWeight = FontWeight.Bold,
                                    color = LevikBlue,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            // Usage History Card (7d / 30d toggle, export, clear)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.traffic_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (trafficHistory.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = onExportTrafficHistory,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.traffic_history_export_btn),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        TextButton(
                            onClick = onClearTrafficHistory,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text(
                                text = stringResource(R.string.traffic_history_clear_btn),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (trafficHistory.isEmpty()) {
                        Text(
                            text = stringResource(R.string.traffic_history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = historyDaysMode == 7,
                                onClick = { historyDaysMode = 7 },
                                label = { Text(stringResource(R.string.traffic_history_7d)) },
                            )
                            FilterChip(
                                selected = historyDaysMode == 30,
                                onClick = { historyDaysMode = 30 },
                                label = { Text(stringResource(R.string.traffic_history_30d)) },
                            )
                        }

                        val displayedHistory = if (historyDaysMode == 7) {
                            trafficHistory.takeLast(7).reversed()
                        } else {
                            trafficHistory.takeLast(30).reversed()
                        }

                        displayedHistory.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.date,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${formatBytes(item.downloadedBytes)} ↓ / ${formatBytes(item.uploadedBytes)} ↑",
                                    fontWeight = FontWeight.Bold,
                                    color = LevikBlue,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            // Diagnostics Button Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.diagnostics_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.diagnostics_running),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = onRunDiagnostics,
                        modifier = Modifier.height(LevikDimensions.ButtonHeight),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.diagnostics_btn), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Text(
                text = stringResource(R.string.stats_privacy_note),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LiveSpeedChartCard(
    history: List<SpeedSample>,
    currentDown: Long,
    currentUp: Long,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(LevikGreen))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Down: ${formatBytes(currentDown)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = LevikGreen,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(LevikBlue))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Up: ${formatBytes(currentUp)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = LevikBlue,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val samples = if (history.isEmpty()) listOf(SpeedSample(0, 0)) else history
            val maxSpeed = samples.maxOf { maxOf(it.downloadBps, it.uploadBps) }.coerceAtLeast(10_000L).toFloat()

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp),
            ) {
                val w = size.width
                val h = size.height
                val step = if (samples.size > 1) w / (samples.size - 1) else w

                val downPath = Path()
                val upPath = Path()
                val downFillPath = Path()

                samples.forEachIndexed { i, sample ->
                    val x = i * step
                    val yDown = h - (sample.downloadBps.toFloat() / maxSpeed) * (h * 0.85f)
                    val yUp = h - (sample.uploadBps.toFloat() / maxSpeed) * (h * 0.85f)
                    if (i == 0) {
                        downPath.moveTo(x, yDown)
                        upPath.moveTo(x, yUp)
                        downFillPath.moveTo(x, h)
                        downFillPath.lineTo(x, yDown)
                    } else {
                        downPath.lineTo(x, yDown)
                        upPath.lineTo(x, yUp)
                        downFillPath.lineTo(x, yDown)
                    }
                }
                downFillPath.lineTo(w, h)
                downFillPath.close()

                drawPath(
                    path = downFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            LevikGreen.copy(alpha = 0.2f),
                            Color.Transparent,
                        ),
                    ),
                )
                drawPath(
                    path = downPath,
                    color = LevikGreen,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
                drawPath(
                    path = upPath,
                    color = LevikBlue,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun StatsCard(icon: Painter, label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = LevikBlue.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = LevikBlue,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    modifier: Modifier,
    account: MobileAccountResponse?,
    profile: PreparedTunnelProfile?,
    session: SessionStatus,
    login: LoginUiState,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSupport: () -> Unit,
    onFreeProxy: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onDeleteAccount: () -> Unit,
    onRelinkAccount: () -> Unit,
    onLogout: () -> Unit,
    routingPreset: RoutingPreset,
    onOpenRoutingPreset: () -> Unit,
    antiDpiPreset: AntiDpiPreset,
    antiDpiEnabled: Boolean,
    onOpenAntiDpi: () -> Unit,
    onAntiDpiChanged: (Boolean) -> Unit,
    autoHealingEnabled: Boolean,
    onAutoHealingChanged: (Boolean) -> Unit,
    onOpenKillSwitch: () -> Unit,
    onOpenWifiProtection: () -> Unit,
    selectedSubscriptionId: String?,
    subscriptionSelectionEnabled: Boolean,
    onSubscriptionSelected: (String) -> Unit,
    onSubscriptionShieldChanged: (String, Boolean) -> Unit,
    onOpenDevices: (SubscriptionSummary) -> Unit,
    splitTunnelMode: SplitTunnelMode,
    splitTunnelSelectedCount: Int,
    onOpenSplitTunneling: () -> Unit,
    dnsProvider: DnsProvider,
    useDoh: Boolean,
    onOpenDns: () -> Unit,
    themeMode: ThemeMode,
    onOpenTheme: () -> Unit,
    onOpenCustomRouting: () -> Unit,
    customDirectCount: Int,
    customProxyCount: Int,
    autoConnectOnBoot: Boolean,
    onAutoConnectBootChanged: (Boolean) -> Unit,
    autoFallbackServer: Boolean,
    onAutoFallbackChanged: (Boolean) -> Unit,
    anonymousTelemetryEnabled: Boolean,
    onAnonymousTelemetryChanged: (Boolean) -> Unit,
    onShareReferralLink: (String) -> Unit,
    onOpenPlans: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val activeSubscriptions = account?.subscriptions.orEmpty().filter {
        it.isActiveAt(Instant.now())
    }
    val subscription = activeSubscriptions.firstOrNull { it.uuid == selectedSubscriptionId }
        ?: activeSubscriptions.firstOrNull()
        ?: account?.subscriptions?.firstOrNull()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onRefresh,
                enabled = !loading && session == SessionStatus.Authenticated,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.content_refresh),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.profile_account),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = account?.user?.userLabel
                            ?: if (session == SessionStatus.SignedOut) {
                                stringResource(R.string.profile_offline_access)
                            } else {
                                stringResource(R.string.account_default_name)
                            },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (session == SessionStatus.SignedOut) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.profile_offline_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            SubscriptionCard(
                subscription = subscription,
                profile = profile,
                loading = loading,
                onOpenDevices = onOpenDevices,
                onShieldChanged = onSubscriptionShieldChanged,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !loading && session == SessionStatus.Authenticated,
                    modifier = Modifier
                        .weight(1f)
                        .height(LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = LevikBlue,
                        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (!loading && session == SessionStatus.Authenticated) LevikBlue.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (!loading && session == SessionStatus.Authenticated) LevikBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.profile_refresh_subscription),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                DistributionRenewPlanButton(
                    onOpenPlans = onOpenPlans,
                    modifier = Modifier
                        .weight(1f)
                        .height(LevikDimensions.ButtonHeight),
                )
            }

            referralSummaryForDisplay(account)?.let { referrals ->
                ReferralCard(
                    referrals = referrals,
                    onShare = { onShareReferralLink(referrals.referralLink) },
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Levik VPN Referral", referrals.referralLink))
                    },
                )
            }

            if (activeSubscriptions.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = stringResource(R.string.profile_active_subscription),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        )
                        activeSubscriptions.forEach { item ->
                            val selected = item.uuid == subscription?.uuid
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        enabled = subscriptionSelectionEnabled && !loading,
                                        role = Role.RadioButton,
                                        onClick = { onSubscriptionSelected(item.uuid) },
                                    )
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = item.expireAt?.let { formatDate(it) }
                                            ?: stringResource(R.string.profile_no_expiry),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                RadioButton(selected = selected, onClick = null)
                            }
                        }
                    }
                }
            }

            // SECURITY & CENSORSHIP CIRCUMVENTION SECTION
            Text(
                text = stringResource(R.string.settings_section_security),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )

            // Anti-DPI / TLS Fragmentation Row
            Surface(
                onClick = onOpenAntiDpi,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.anti_dpi_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (antiDpiPreset != AntiDpiPreset.OFF) {
                                stringResource(R.string.anti_dpi_active_preset, antiDpiPreset.titleRu)
                            } else {
                                stringResource(R.string.anti_dpi_desc)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Switch(
                        checked = antiDpiEnabled,
                        onCheckedChange = onAntiDpiChanged,
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
            }

            // Auto-Healing Switch
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_healing_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.auto_healing_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Switch(
                        checked = autoHealingEnabled,
                        onCheckedChange = onAutoHealingChanged,
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
            }

            // Anonymous Censorship Radar Switch
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.censorship_radar_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.censorship_radar_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Switch(
                        checked = anonymousTelemetryEnabled,
                        onCheckedChange = onAnonymousTelemetryChanged,
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
            }

            // Kill Switch Dialog Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenKillSwitch),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.kill_switch_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.kill_switch_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Wi-Fi Protection Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenWifiProtection),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.wifi_protection_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.wifi_protection_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // NETWORK & PROTOCOL SETTINGS SECTION
            Text(
                text = stringResource(R.string.settings_section_network),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )

            // Routing Preset Selector
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRoutingPreset),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.routing_preset_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = routingPreset.titleRu,
                            color = LevikBlue,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Split Tunneling Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSplitTunneling),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.split_tunneling_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when (splitTunnelMode) {
                                SplitTunnelMode.OFF -> stringResource(R.string.split_tunnel_off)
                                SplitTunnelMode.ALLOWED -> stringResource(
                                    R.string.split_tunnel_include_count,
                                    splitTunnelSelectedCount,
                                )
                                SplitTunnelMode.DISALLOWED -> stringResource(
                                    R.string.split_tunnel_exclude_count,
                                    splitTunnelSelectedCount,
                                )
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // DNS Settings Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDns),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dns_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when (dnsProvider) {
                                DnsProvider.CLOUDFLARE -> "Cloudflare (1.1.1.1)"
                                DnsProvider.GOOGLE -> "Google (8.8.8.8)"
                                DnsProvider.ADGUARD -> "AdGuard DNS (Рекламорез)"
                                DnsProvider.QUAD9 -> "Quad9 (9.9.9.9)"
                                DnsProvider.CUSTOM -> "Пользовательский"
                            } + if (useDoh) " • DoH" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Custom Routing Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenCustomRouting),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.custom_routing_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${stringResource(R.string.custom_direct_domains)}: $customDirectCount | ${stringResource(R.string.custom_proxy_domains)}: $customProxyCount",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Auto-fallback Server Switch
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_fallback),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.auto_fallback_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Switch(
                        checked = autoFallbackServer,
                        onCheckedChange = onAutoFallbackChanged,
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
            }

            // GENERAL & APPEARANCE SETTINGS SECTION
            Text(
                text = stringResource(R.string.settings_section_general),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )

            // Battery Optimization Guidance Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRequestBatteryOptimization),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.battery_optimization_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.battery_optimization_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            DistributionUpdateSettingsItem(onCheckForUpdates = onCheckForUpdates)

            // Theme Setting Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTheme),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.theme_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when (themeMode) {
                                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                ThemeMode.AMOLED -> stringResource(R.string.theme_amoled)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Application Logs Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLogs),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.logs_viewer_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.logs_viewer_btn),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Device Protection info
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = LevikGreen.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shield),
                                contentDescription = null,
                                tint = LevikGreen,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.profile_device_bound),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.profile_device_bound_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onFreeProxy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LevikDimensions.ButtonHeight),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_servers),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.free_proxy_button), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onSupport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LevikDimensions.ButtonHeight),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.profile_support), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onPrivacyPolicy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LevikDimensions.ButtonHeight),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_privacy),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profile_privacy_policy), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onDeleteAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LevikDimensions.ButtonHeight),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_account),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profile_delete_account), fontWeight = FontWeight.SemiBold)
            }
            if (session == SessionStatus.SignedOut) {
                OutlinedButton(
                    onClick = onRelinkAccount,
                    enabled = login !is LoginUiState.Loading &&
                        login !is LoginUiState.Waiting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_login),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (login is LoginUiState.Waiting) {
                                R.string.profile_relink_waiting
                            } else {
                                R.string.profile_relink
                            },
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LevikDimensions.ButtonHeight),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_logout),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profile_logout), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

internal fun referralSummaryForDisplay(account: MobileAccountResponse?): ReferralSummary? =
    account?.referrals

@Composable
private fun AntiDpiDialog(
    enabled: Boolean,
    currentPreset: AntiDpiPreset,
    customPackets: String,
    customLength: String,
    customInterval: String,
    onPresetSelected: (AntiDpiPreset) -> Unit,
    onCustomParamsChanged: (String, String, String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var isEnabled by remember(enabled) { mutableStateOf(enabled) }
    var selectedPreset by remember(currentPreset) { mutableStateOf(currentPreset) }
    var packetsText by remember(customPackets) { mutableStateOf(customPackets) }
    var lengthText by remember(customLength) { mutableStateOf(customLength) }
    var intervalText by remember(customInterval) { mutableStateOf(customInterval) }
    var isCustomFormatError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = stringResource(R.string.anti_dpi_dialog_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.anti_dpi_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )

                // Master Toggle Switch
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.anti_dpi_enable_toggle),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                isEnabled = checked
                                if (checked && selectedPreset == AntiDpiPreset.OFF) {
                                    selectedPreset = AntiDpiPreset.TLS_HELLO
                                }
                            },
                            colors = LevikSwitchDefaults.colors(),
                        )
                    }
                }

                if (isEnabled) {
                    Text(
                        text = stringResource(R.string.anti_dpi_presets_header),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    AntiDpiPreset.entries.filter { it != AntiDpiPreset.OFF }.forEach { preset ->
                        val isSelected = selectedPreset == preset
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedPreset = preset },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedPreset = preset },
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = preset.titleRu,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = preset.descriptionRu,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (preset != AntiDpiPreset.CUSTOM) {
                                        Spacer(Modifier.height(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                        ) {
                                            Text(
                                                text = "packets: ${preset.defaultPackets} | length: ${preset.defaultLength} | interval: ${preset.defaultInterval} ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (selectedPreset == AntiDpiPreset.CUSTOM) {
                        Text(
                            text = stringResource(R.string.anti_dpi_custom_header),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        OutlinedTextField(
                            value = packetsText,
                            onValueChange = {
                                packetsText = it
                                isCustomFormatError = false
                            },
                            label = { Text(stringResource(R.string.anti_dpi_packets_label)) },
                            placeholder = { Text(stringResource(R.string.anti_dpi_packets_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = lengthText,
                            onValueChange = {
                                lengthText = it
                                isCustomFormatError = false
                            },
                            label = { Text(stringResource(R.string.anti_dpi_length_label)) },
                            placeholder = { Text(stringResource(R.string.anti_dpi_length_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = {
                                intervalText = it
                                isCustomFormatError = false
                            },
                            label = { Text(stringResource(R.string.anti_dpi_interval_label)) },
                            placeholder = { Text(stringResource(R.string.anti_dpi_interval_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (isCustomFormatError) {
                            Text(
                                text = stringResource(R.string.anti_dpi_custom_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isEnabled) {
                        onEnabledChanged(false)
                    } else {
                        if (selectedPreset == AntiDpiPreset.CUSTOM) {
                            val cleanPackets = packetsText.trim()
                            val cleanLength = lengthText.trim()
                            val cleanInterval = intervalText.trim()
                            val safeRegex = Regex("^[a-zA-Z0-9,-]+$")
                            if (!cleanPackets.matches(safeRegex) || !cleanLength.matches(safeRegex) || !cleanInterval.matches(safeRegex)) {
                                isCustomFormatError = true
                                return@Button
                            }
                            onCustomParamsChanged(cleanPackets, cleanLength, cleanInterval)
                        } else {
                            onPresetSelected(selectedPreset)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.anti_dpi_apply_btn), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun RoutingPresetDialog(
    currentPreset: RoutingPreset,
    onPresetSelected: (RoutingPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.routing_preset_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoutingPreset.entries.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentPreset == preset,
                                onClick = { onPresetSelected(preset) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentPreset == preset, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(preset.titleRu, fontWeight = FontWeight.SemiBold)
                            Text(
                                preset.descriptionRu,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun WifiProtectionDialog(
    autoConnect: Boolean,
    trustedSsids: Set<String>,
    onAutoConnectChanged: (Boolean) -> Unit,
    onAddTrusted: (String) -> Unit,
    onRemoveTrusted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newSsidText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.wifi_protection_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.untrusted_wifi_auto_connect),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.untrusted_wifi_auto_connect_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoConnect,
                        onCheckedChange = onAutoConnectChanged,
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.trusted_wifi_list_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newSsidText,
                        onValueChange = { newSsidText = it },
                        placeholder = { Text(stringResource(R.string.add_trusted_wifi_hint)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newSsidText.isNotBlank()) {
                                onAddTrusted(newSsidText)
                                newSsidText = ""
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(LevikDimensions.ButtonHeight),
                    ) {
                        Text(stringResource(R.string.add), fontWeight = FontWeight.SemiBold)
                    }
                }
                val list = trustedSsids.toList()
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_trusted_wifi),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list) { ssid ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(ssid, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onRemoveTrusted(ssid) }) {
                                    Text(
                                        stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun KillSwitchDialog(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.kill_switch_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.kill_switch_enable),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.kill_switch_app_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
                HorizontalDivider()
                Text(stringResource(R.string.kill_switch_dialog_body))
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.open_vpn_settings), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun LogsViewerDialog(
    logs: List<LogEntry>,
    formattedLogs: String,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
    onSendSupport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.logs_viewer_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(400.dp)) {
                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(logs) { entry ->
                            Text(
                                text = entry.toFormattedString(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onClear,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(LevikDimensions.ButtonHeight),
                ) {
                    Text(stringResource(R.string.logs_clear_btn), fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { onCopy(formattedLogs) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(LevikDimensions.ButtonHeight),
                ) {
                    Text(stringResource(R.string.logs_copy_btn), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onSendSupport,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(LevikDimensions.ButtonHeight),
                ) {
                    Text(stringResource(R.string.logs_send_support), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun SplitTunnelModeDialog(
    currentMode: SplitTunnelMode,
    selectedCount: Int,
    onModeSelected: (SplitTunnelMode) -> Unit,
    onSelectApps: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.split_tunneling_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SplitTunnelMode.entries.forEach { mode ->
                    val label = when (mode) {
                        SplitTunnelMode.OFF -> stringResource(R.string.split_tunnel_mode_off)
                        SplitTunnelMode.DISALLOWED -> stringResource(R.string.split_tunnel_mode_disallowed)
                        SplitTunnelMode.ALLOWED -> stringResource(R.string.split_tunnel_mode_allowed)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentMode == mode,
                                onClick = { onModeSelected(mode) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentMode == mode, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (currentMode != SplitTunnelMode.OFF) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSelectApps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LevikDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.split_tunnel_select_apps, selectedCount), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun AppSelectorDialog(
    apps: List<InstalledAppItem>,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.split_tunnel_select_apps, selectedPackages.size), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(420.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.split_tunnel_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                if (filteredApps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.split_tunnel_no_apps),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredApps, key = InstalledAppItem::packageName) { app ->
                            val isSelected = selectedPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTogglePackage(app.packageName) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon.toBitmap(40, 40).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shield),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = LevikBlue,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = isSelected,
                                    onCheckedChange = { onTogglePackage(app.packageName) },
                                    colors = LevikSwitchDefaults.colors(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun DnsProviderDialog(
    currentProvider: DnsProvider,
    customIp: String,
    useDoh: Boolean,
    customDohUrl: String,
    onProviderSelected: (DnsProvider) -> Unit,
    onCustomIpChanged: (String) -> Unit,
    onUseDohChanged: (Boolean) -> Unit,
    onCustomDohUrlChanged: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customText by remember { mutableStateOf(customIp) }
    var customDohText by remember { mutableStateOf(customDohUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.dns_settings_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // DoH Global Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.doh_enable_toggle),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.doh_enable_toggle_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useDoh,
                        onCheckedChange = onUseDohChanged,
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DnsProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentProvider == provider,
                                onClick = { onProviderSelected(provider) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentProvider == provider, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(provider.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                provider.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (currentProvider == DnsProvider.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customText,
                        onValueChange = {
                            customText = it
                            onCustomIpChanged(it)
                        },
                        label = { Text(stringResource(R.string.dns_custom_ip_label)) },
                        placeholder = { Text(stringResource(R.string.dns_custom_ip_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (useDoh) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customDohText,
                            onValueChange = {
                                customDohText = it
                                onCustomDohUrlChanged(it)
                            },
                            label = { Text(stringResource(R.string.doh_custom_url_label)) },
                            placeholder = { Text(stringResource(R.string.doh_custom_url_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun ThemeDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.theme_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                        ThemeMode.AMOLED -> stringResource(R.string.theme_amoled)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentTheme == mode,
                                onClick = { onThemeSelected(mode) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentTheme == mode, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun CustomRoutingDialog(
    directDomains: Set<String>,
    proxyDomains: Set<String>,
    onAddDirect: (String) -> Unit,
    onRemoveDirect: (String) -> Unit,
    onAddProxy: (String) -> Unit,
    onRemoveProxy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var newDomainText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.custom_routing_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(380.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Direct (${directDomains.size})" to 0, "Proxy (${proxyDomains.size})" to 1).forEach { (label, tab) ->
                        val isSelected = selectedTab == tab
                        Surface(
                            onClick = { selectedTab = tab },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) LevikBlue else MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) LevikBlue else MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newDomainText,
                        onValueChange = { newDomainText = it },
                        placeholder = { Text(stringResource(R.string.add_domain_hint)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newDomainText.isNotBlank()) {
                                if (selectedTab == 0) onAddDirect(newDomainText)
                                else onAddProxy(newDomainText)
                                newDomainText = ""
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(LevikDimensions.ButtonHeight),
                    ) {
                        Text(stringResource(R.string.add), fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val currentList = if (selectedTab == 0) directDomains.toList() else proxyDomains.toList()
                if (currentList.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_custom_domains),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(currentList) { domain ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(domain, style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    onClick = {
                                        if (selectedTab == 0) onRemoveDirect(domain)
                                        else onRemoveProxy(domain)
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun DiagnosticsDialog(
    running: Boolean,
    report: DiagnosticReport?,
    isSharingNote: Boolean,
    onCopy: (String) -> Unit,
    onSendSupport: () -> Unit,
    onShareSupportNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.diagnostics_report_title), fontWeight = FontWeight.Bold) },
        text = {
            if (running || report == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.diagnostics_running))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (report.ipInfo != null) {
                        Text(
                            text = "IP: ${report.ipInfo.address} (${report.ipInfo.city.orEmpty()}, ${report.ipInfo.countryCode.orEmpty()})",
                            fontWeight = FontWeight.Bold,
                        )
                        Text("ISP: ${report.ipInfo.provider.orEmpty()} (ASN: ${report.ipInfo.asn ?: "Unknown"})")
                        Text(
                            text = if (report.ipInfo.isProtected) "✓ Защищен (Узел LevikVPN)" else "⚠ Прямое соединение (Без VPN)",
                            color = if (report.ipInfo.isProtected) LevikGreen else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text("External IP: Unknown", fontWeight = FontWeight.Bold)
                    }
                    Text("Server: ${report.serverName ?: "None"}")
                    HorizontalDivider()
                    Text("Services:", fontWeight = FontWeight.SemiBold)
                    report.serviceChecks.forEach { check ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(check.name)
                            Text(
                                text = if (check.success) "✓ ${check.latencyMs}ms" else "✗ Failed",
                                color = if (check.success) LevikGreen else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!running && report != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onCopy(report.toFormattedString()) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                        .height(LevikDimensions.ButtonHeight),
                        ) {
                            Text(stringResource(R.string.diagnostics_copy), fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onShareSupportNote,
                            enabled = !isSharingNote,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                        .height(LevikDimensions.ButtonHeight),
                        ) {
                            if (isSharingNote) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.support_note_export_btn), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Button(
                        onClick = onSendSupport,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                        .height(LevikDimensions.ButtonHeight),
                    ) {
                        Text(stringResource(R.string.diagnostics_send_support), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun PauseVpnDialog(
    onDismiss: () -> Unit,
    onPause: (Int) -> Unit,
) {
    val options = listOf(5, 15, 60)
    var selectedMinutes by remember { mutableStateOf(15) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.pause_vpn_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.pause_vpn_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedMinutes == minutes,
                                onClick = { selectedMinutes = minutes },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedMinutes == minutes, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = when (minutes) {
                                5 -> "5 минут"
                                15 -> "15 минут"
                                else -> "1 час"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPause(selectedMinutes) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.pause_vpn_btn), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun SupportNoteDialog(
    noteUrl: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenSupport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.support_note_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.support_note_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                        text = noteUrl,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSupport,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.support_note_open_support), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { onCopy(noteUrl) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.support_note_copy), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun ReferralCard(
    referrals: ReferralSummary,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = LevikBlue.copy(alpha = 0.15f),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_crown),
                            contentDescription = null,
                            tint = LevikBlue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.referral_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.referral_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.referral_terms_base),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.referral_terms_tiers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.referral_stats_invited, referrals.invited),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.referral_stats_rewarded, referrals.rewarded),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .height(LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LevikBlue,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.referral_share_btn), fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .weight(1f)
                        .height(LevikDimensions.ButtonHeight),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = LevikBlue,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LevikBlue.copy(alpha = 0.5f)),
                ) {
                    Text(stringResource(R.string.referral_copy_btn), fontWeight = FontWeight.SemiBold, color = LevikBlue)
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionSummary?,
    profile: PreparedTunnelProfile?,
    loading: Boolean,
    onOpenDevices: (SubscriptionSummary) -> Unit = {},
    onShieldChanged: (String, Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.profile_subscription),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            ProfileLine(
                label = stringResource(R.string.profile_plan),
                value = subscription?.title
                    ?: if (profile != null) {
                        stringResource(R.string.profile_local_plan)
                    } else {
                        stringResource(R.string.not_available)
                    },
            )
            ProfileLine(
                label = stringResource(R.string.profile_status),
                value = when {
                    subscription != null -> subscription.status.localizedSubscriptionStatus()
                    profile != null -> stringResource(R.string.profile_local_status)
                    else -> stringResource(R.string.not_available)
                },
            )
            ProfileLine(
                label = stringResource(R.string.profile_expires),
                value = subscription?.expireAt?.let { formatDate(it) }
                    ?: profile?.subscriptionExpiresAt?.let { formatDate(it) }
                    ?: if (profile != null) {
                        stringResource(R.string.profile_local_never_expires)
                    } else {
                        stringResource(R.string.not_available)
                    },
            )
            ProfileLine(
                label = stringResource(R.string.profile_traffic),
                value = subscription?.let {
                    "${formatBytes(it.traffic.usedBytes)} / ${formatBytes(it.traffic.limitBytes)}"
                } ?: stringResource(R.string.not_available),
            )
            ProfileLine(
                label = stringResource(R.string.profile_devices),
                value = subscription?.let { "${it.devices.used} / ${it.devices.limit}" }
                    ?: stringResource(R.string.not_available),
            )

            subscription?.components?.let { components ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.multi_subscription_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                ProfileLine(
                    label = stringResource(R.string.multi_regular_component),
                    value = stringResource(
                        R.string.multi_component_usage,
                        formatBytes(components.regular.traffic.usedBytes),
                        formatBytes(components.regular.traffic.limitBytes),
                        components.regular.devices.used,
                        components.regular.devices.limit,
                    ),
                )
                ProfileLine(
                    label = stringResource(R.string.multi_mobile_component),
                    value = stringResource(
                        R.string.multi_component_usage,
                        formatBytes(components.mobile.traffic.usedBytes),
                        formatBytes(components.mobile.traffic.limitBytes),
                        components.mobile.devices.used,
                        components.mobile.devices.limit,
                    ),
                )
            }

            if (subscription?.shield?.supported == true) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.levik_shield_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.levik_shield_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = subscription.shield.enabled,
                        enabled = !loading,
                        onCheckedChange = { enabled ->
                            onShieldChanged(subscription.uuid, enabled)
                        },
                        colors = LevikSwitchDefaults.colors(),
                    )
                }
            }

            if (subscription != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onOpenDevices(subscription) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = LevikBlue,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LevikBlue.copy(alpha = 0.4f)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = LevikBlue,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.devices_manage_btn),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LevikBlue,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionDevicesDialog(
    subscription: SubscriptionSummary,
    onRevokeDevice: (subscriptionId: String, deviceId: String) -> Unit,
    onOpenPlans: () -> Unit,
    onDismiss: () -> Unit,
) {
    var deviceToRevoke by remember { mutableStateOf<com.leviknet.vpn.core.network.DeviceItem?>(null) }

    if (deviceToRevoke != null) {
        val dev = deviceToRevoke!!
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.device_revoke_confirm_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.device_revoke_confirm_message, dev.label))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeDevice(subscription.uuid, dev.id)
                        deviceToRevoke = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.device_revoke_btn), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deviceToRevoke = null },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(stringResource(R.string.devices_dialog_title), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.devices_count_format, subscription.devices.used, subscription.devices.limit),
                    style = MaterialTheme.typography.labelMedium,
                    color = LevikBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.devices_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (subscription.devices.items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.devices_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    subscription.devices.items.forEach { dev ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_shield),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = LevikBlue,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = dev.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = dev.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (subscription.actions.revokeDevice) {
                                    Spacer(Modifier.width(6.dp))
                                    OutlinedButton(
                                        onClick = { deviceToRevoke = dev },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(34.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.device_revoke_btn),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                DistributionAddonActions(
                    slotAddon = subscription.actions.slotAddon,
                    trafficAddon = subscription.actions.trafficAddon,
                    onOpenPlans = onOpenPlans,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun ProfileLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val GIB = 1024.0 * 1024.0 * 1024.0
private const val MIB = 1024.0 * 1024.0
private const val KIB = 1024.0

@Composable
private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val remSec = s % 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_minutes_seconds, minutes, remSec)
        else -> stringResource(R.string.duration_seconds, remSec)
    }
}

private val DATE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDate(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    val local = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    DATE_FORMATTER.format(local)
}.getOrDefault(iso)

@Composable
private fun UiMessage.localized(): String = stringResource(
    when (this) {
        UiMessage.GENERIC_ERROR -> R.string.generic_error
        UiMessage.SESSION_EXPIRED -> R.string.session_expired
        UiMessage.SUBSCRIPTION_REQUIRED -> R.string.subscription_required
        UiMessage.PROFILE_UNAVAILABLE -> R.string.profile_unavailable
        UiMessage.DEVICE_LIMIT_REACHED -> R.string.device_limit_reached
        UiMessage.RATE_LIMITED -> R.string.rate_limited
        UiMessage.VPN_PERMISSION_DENIED -> R.string.vpn_permission_denied
        UiMessage.NOTIFICATION_PERMISSION_DENIED -> R.string.notification_permission_denied
        UiMessage.LOCATION_PERMISSION_DENIED -> R.string.location_permission_denied
        UiMessage.LOGIN_DENIED -> R.string.login_denied
        UiMessage.ATTESTATION_UNAVAILABLE -> R.string.attestation_unavailable
        UiMessage.SUBSCRIPTION_UPDATED -> R.string.subscription_updated
        UiMessage.SERVER_PING_UNAVAILABLE -> R.string.server_ping_unavailable
        UiMessage.DIAGNOSTICS_COPIED -> R.string.diagnostics_copied
        UiMessage.DEVICE_REVOKED_SUCCESS -> R.string.device_revoked_success
        UiMessage.DEVICE_REVOKE_FAILED -> R.string.device_revoke_failed
        UiMessage.TRAFFIC_HISTORY_CLEARED -> R.string.traffic_history_cleared
        UiMessage.TRAFFIC_HISTORY_EXPORTED -> R.string.traffic_history_exported
    },
)

@Composable
private fun VpnConnectionState.localized(): String = stringResource(
    when (this) {
        VpnConnectionState.DISCONNECTED -> R.string.status_disconnected
        VpnConnectionState.CONNECTING -> R.string.status_connecting
        VpnConnectionState.CONNECTED -> R.string.status_connected
        VpnConnectionState.PAUSED -> R.string.status_paused
        VpnConnectionState.RECONNECTING -> R.string.status_reconnecting
        VpnConnectionState.STOPPING -> R.string.status_stopping
        VpnConnectionState.ERROR -> R.string.status_error
        VpnConnectionState.LOCKDOWN -> R.string.status_lockdown
    },
)

@Composable
private fun VpnFailure.localized(): String = stringResource(
    when (this) {
        VpnFailure.CORE_UNAVAILABLE -> R.string.core_unavailable
        VpnFailure.INVALID_PROFILE -> R.string.core_rejected_config
        VpnFailure.PERMISSION_REVOKED -> R.string.vpn_permission_denied
        VpnFailure.NETWORK -> R.string.generic_error
    },
)

@Composable
private fun String?.localizedSubscriptionStatus(): String = stringResource(
    when {
        this.equals("active", ignoreCase = true) -> R.string.active
        this.equals("expired", ignoreCase = true) -> R.string.expired
        else -> R.string.inactive
    },
)

@Composable
private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0).toDouble()
    return when {
        value >= GIB -> stringResource(R.string.bytes_gb, value / GIB)
        value >= MIB -> stringResource(R.string.bytes_mb, value / MIB)
        value >= KIB -> stringResource(R.string.bytes_kb, value / KIB)
        else -> stringResource(R.string.bytes_b, value)
    }
}

enum class NavigationDestination(
    val tab: AppTab,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
) {
    HOME(AppTab.HOME, R.drawable.ic_home, R.string.nav_home),
    SERVERS(AppTab.SERVERS, R.drawable.ic_servers, R.string.nav_servers),
    STATS(AppTab.STATS, R.drawable.ic_stats, R.string.nav_stats),
    PROFILE(AppTab.PROFILE, R.drawable.ic_profile, R.string.nav_profile),
}
