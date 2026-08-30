package com.leviknet.vpn.ui

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.leviknet.vpn.AppContainer
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.auth.AuthorizationChallengePolicy
import com.leviknet.vpn.core.auth.ChallengeAuthorization
import com.leviknet.vpn.core.auth.DeepLinkDestination
import com.leviknet.vpn.core.auth.DeepLinkRouter
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.logger.LogEntry
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.CensorshipRadarWorker
import com.leviknet.vpn.core.network.CatalogResponse
import com.leviknet.vpn.core.network.AuthChallengeResponse
import com.leviknet.vpn.core.network.DiagnosticReport
import com.leviknet.vpn.core.network.LevikStatusSnapshot
import com.leviknet.vpn.core.network.MobileAccountResponse
import com.leviknet.vpn.core.network.NetworkDiagnostics
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.core.network.TrafficSummary
import com.leviknet.vpn.core.network.WhitelistDetector
import com.leviknet.vpn.core.network.WhitelistMode
import com.leviknet.vpn.data.AntiDpiPreset
import com.leviknet.vpn.data.AppRepository
import com.leviknet.vpn.data.AppSettings
import com.leviknet.vpn.data.DailyTraffic
import com.leviknet.vpn.data.DnsProvider
import com.leviknet.vpn.data.LoginPollResult
import com.leviknet.vpn.data.RoutingPreset
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.data.SplitTunnelMode
import com.leviknet.vpn.data.ThemeMode
import com.leviknet.vpn.data.TrafficHistoryStore
import com.leviknet.vpn.data.isActiveAt
import com.leviknet.vpn.vpn.PreparedTunnelProfile
import com.leviknet.vpn.vpn.ServerPinger
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnController
import com.leviknet.vpn.vpn.VpnSnapshot
import com.leviknet.vpn.vpn.isEligibleForAutomaticSelection
import com.leviknet.vpn.vpn.isMobileServer
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import com.leviknet.vpn.core.network.MobileApiClient
import com.leviknet.vpn.core.update.AppUpdateManager
import com.leviknet.vpn.core.update.AppUpdateDto
import com.leviknet.vpn.core.update.UpdateState

class AppViewModel(
    private val repository: AppRepository,
    private val vpnController: VpnController,
    private val settings: AppSettings,
    private val apiClient: MobileApiClient,
    private val updateManager: AppUpdateManager,
    private val trafficHistoryStore: TrafficHistoryStore? = null,
    private val appContext: Context? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    private val effectChannel = Channel<AppEffect>(Channel.BUFFERED)
    private var loginStartJob: Job? = null
    private var loginPollJob: Job? = null
    private var updateCheckJob: Job? = null
    private var pendingOnboardingAction: OnboardingAction? = null
    private var connectionPending = false
    private var searchDebounceJob: Job? = null
    private var isRefreshingAccount = false
    private var profileRefreshPending = false
    private var lteTrafficSyncJob: Job? = null
    private var whitelistDetectionJob: Job? = null
    private var pendingLteAction: PendingLteAction? = null
    private var pendingWifiAutoConnect = false
    private val serverPingMutex = Mutex()
    private val perAppBaselineMutex = Mutex()
    private val lteTrafficAccumulator = LteTrafficAccumulator()
    private val whitelistDetector = appContext?.let(::WhitelistDetector)

    /** uid -> (rx, tx) captured at VPN connect; per-app stats show deltas from it. */
    @Volatile
    private var perAppTrafficBaseline: Map<Int, Pair<Long, Long>> = emptyMap()

    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.session.collect { session ->
                mutableState.update { it.copy(session = session) }
            }
        }
        viewModelScope.launch {
            repository.account.collect { account ->
                mutableState.update { current ->
                    val withAccount = current.copy(account = account)
                    withAccount.copy(
                        lteTraffic = calculateLteTraffic(withAccount, current.vpn),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.tunnelProfile.collect { profile ->
                val selected = repository.selectedServerId()
                    ?.takeIf { id -> profile?.servers?.any { it.id == id } == true }
                    ?: profile?.servers?.firstOrNull(TunnelServer::isEligibleForAutomaticSelection)?.id
                mutableState.update {
                    it.copy(profile = profile, selectedServerId = selected)
                }
            }
        }
        viewModelScope.launch {
            var previousState: VpnConnectionState? = null
            vpnController.state.collect { vpn ->
                if (previousState != VpnConnectionState.CONNECTED &&
                    vpn.state == VpnConnectionState.CONNECTED
                ) {
                    capturePerAppTrafficBaseline()
                }
                previousState = vpn.state
                mutableState.update { current ->
                    val newHistory = (current.liveSpeedHistory + SpeedSample(
                        downloadBps = vpn.downloadBytesPerSecond,
                        uploadBps = vpn.uploadBytesPerSecond,
                    )).takeLast(30)
                    val withVpn = current.copy(vpn = vpn, liveSpeedHistory = newHistory)
                    withVpn.copy(lteTraffic = calculateLteTraffic(withVpn, vpn))
                }
                updateLteTrafficSync(vpn)
            }
        }
        viewModelScope.launch {
            settings.routingPreset.collect { preset ->
                mutableState.update { it.copy(routingPreset = preset) }
            }
        }
        viewModelScope.launch {
            settings.bypassRussianTraffic.collect { enabled ->
                mutableState.update { it.copy(bypassRussianTraffic = enabled) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiEnabled.collect { enabled ->
                mutableState.update { it.copy(antiDpiEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiPreset.collect { preset ->
                mutableState.update { it.copy(antiDpiPreset = preset) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiPackets.collect { packets ->
                mutableState.update { it.copy(antiDpiPackets = packets) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiLength.collect { length ->
                mutableState.update { it.copy(antiDpiLength = length) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiInterval.collect { interval ->
                mutableState.update { it.copy(antiDpiInterval = interval) }
            }
        }
        viewModelScope.launch {
            settings.autoHealingEnabled.collect { enabled ->
                mutableState.update { it.copy(autoHealingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.killSwitchEnabled.collect { enabled ->
                mutableState.update { it.copy(killSwitchEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.autoConnectUntrustedWifi.collect { enabled ->
                mutableState.update { it.copy(autoConnectUntrustedWifi = enabled) }
            }
        }
        viewModelScope.launch {
            settings.trustedWifiSsids.collect { ssids ->
                mutableState.update { it.copy(trustedWifiSsids = ssids) }
            }
        }
        viewModelScope.launch {
            settings.useDoh.collect { enabled ->
                mutableState.update { it.copy(useDoh = enabled) }
            }
        }
        viewModelScope.launch {
            settings.customDohUrl.collect { url ->
                mutableState.update { it.copy(customDohUrl = url) }
            }
        }
        viewModelScope.launch {
            settings.selectedSubscriptionId.collect { subscriptionId ->
                mutableState.update { it.copy(selectedSubscriptionId = subscriptionId) }
            }
        }
        viewModelScope.launch {
            settings.automaticServer.collect { enabled ->
                mutableState.update { it.copy(automaticServer = enabled) }
            }
        }
        viewModelScope.launch {
            settings.splitTunnelMode.collect { mode ->
                mutableState.update { it.copy(splitTunnelMode = mode) }
            }
        }
        viewModelScope.launch {
            settings.splitTunnelPackages.collect { pkgs ->
                mutableState.update { it.copy(splitTunnelPackages = pkgs) }
            }
        }
        viewModelScope.launch {
            settings.dnsProvider.collect { dns ->
                mutableState.update { it.copy(dnsProvider = dns) }
            }
        }
        viewModelScope.launch {
            settings.customDnsIpv4.collect { ip ->
                mutableState.update { it.copy(customDnsIpv4 = ip) }
            }
        }
        viewModelScope.launch {
            settings.themeMode.collect { theme ->
                mutableState.update { it.copy(themeMode = theme) }
            }
        }
        viewModelScope.launch {
            settings.useDynamicColors.collect { dynamic ->
                mutableState.update { it.copy(useDynamicColors = dynamic) }
            }
        }
        viewModelScope.launch {
            settings.autoConnectOnBoot.collect { autoBoot ->
                mutableState.update { it.copy(autoConnectOnBoot = autoBoot) }
            }
        }
        viewModelScope.launch {
            settings.autoFallbackServer.collect { fallback ->
                mutableState.update { it.copy(autoFallbackServer = fallback) }
            }
        }
        viewModelScope.launch {
            settings.favoriteServerIds.collect { favs ->
                mutableState.update { it.copy(favoriteServerIds = favs) }
            }
        }
        viewModelScope.launch {
            settings.customDirectDomains.collect { domains ->
                mutableState.update { it.copy(customDirectDomains = domains) }
            }
        }
        viewModelScope.launch {
            settings.customProxyDomains.collect { domains ->
                mutableState.update { it.copy(customProxyDomains = domains) }
            }
        }
        viewModelScope.launch {
            settings.anonymousTelemetryEnabled.collect { enabled ->
                mutableState.update { it.copy(anonymousTelemetryEnabled = enabled) }
            }
        }
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            viewModelScope.launch {
                updateManager.state.collect { updateState ->
                    mutableState.update { it.copy(updateState = updateState) }
                }
            }
        }
        trafficHistoryStore?.let { store ->
            viewModelScope.launch {
                store.history.collect { list ->
                    mutableState.update { it.copy(trafficHistory = list) }
                }
            }
        }
        viewModelScope.launch {
            serverPingLoop()
        }
        refreshWhitelistStatus()
        viewModelScope.launch { refreshLevikStatus() }
        viewModelScope.launch {
            val hadCachedProfile = repository.cachedTunnel() != null
            repository.initialize()
            val account = repository.account.value
            if (account != null) {
                try {
                    val profile = reconcileAuthenticatedProfile(
                        account = account,
                        hadCachedProfile = hadCachedProfile,
                        forceProfileRefresh = false,
                    )
                    if (profile != null && settings.automaticServer.value) {
                        selectBestServer(profile)
                    }
                } catch (error: Throwable) {
                    syncCachedProfile()
                    mutableState.update { it.copy(message = error.toUiMessage()) }
                }
            } else {
                val profile = syncCachedProfile()
                if (shouldDisconnectAfterProfileRemoval(
                        hadCachedProfile = hadCachedProfile,
                        hasCachedProfile = profile != null,
                        vpnState = vpnController.state.value.state,
                    )
                ) {
                    vpnController.disconnect()
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        mutableState.update { it.copy(tab = tab) }
        if (tab == AppTab.SERVERS &&
            mutableState.value.profile == null &&
            mutableState.value.vpn.state in setOf(
                VpnConnectionState.DISCONNECTED,
                VpnConnectionState.ERROR,
            )
        ) {
            loadServers()
        }
    }

    private fun loadServers() {
        if (mutableState.value.refreshing) return
        viewModelScope.launch {
            val account = mutableState.value.account ?: return@launch
            val activeSubs = account.subscriptions.filter { it.isActiveAt(Instant.now()) }
            if (activeSubs.isEmpty()) {
                mutableState.update { it.copy(message = UiMessage.SUBSCRIPTION_REQUIRED) }
                return@launch
            }
            val preferredId = settings.selectedSubscriptionId.value
            val orderedSubs = activeSubs.sortedByDescending { it.uuid == preferredId }

            mutableState.update { it.copy(refreshing = true, message = null) }
            var lastError: Throwable? = null
            var success = false

            for (subscription in orderedSubs) {
                try {
                    val profile = prepareTunnelWithRetry(subscription.uuid)
                    ensureSelectedSubscription(subscription.uuid)
                    val selected = selectServerForProfile(profile)
                    mutableState.update {
                        it.copy(profile = profile, selectedServerId = selected, message = null)
                    }
                    success = true
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    AppLogger.w("AppViewModel", "Subscription profile failed to load: ${error.message}")
                    lastError = error
                }
            }

            if (!success && lastError != null) {
                mutableState.update { it.copy(message = lastError.toUiMessage()) }
            }
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    fun beginTelegramLogin() = beginOnboarding(OnboardingAction.TELEGRAM_LOGIN)

    fun beginWebsiteLogin() = beginOnboarding(OnboardingAction.WEBSITE_LOGIN)

    fun activateDeviceTrial() = beginOnboarding(OnboardingAction.DEVICE_TRIAL)

    fun activateLteTrial() {
        if (mutableState.value.session == SessionStatus.Authenticated) {
            if (
                mutableState.value.account?.trial?.eligible == true &&
                mutableState.value.login !is LoginUiState.Loading &&
                !mutableState.value.refreshing
            ) {
                startMobileTrial()
            }
        } else {
            beginOnboarding(OnboardingAction.TELEGRAM_LTE_TRIAL)
        }
    }

    private fun beginOnboarding(action: OnboardingAction) {
        if (mutableState.value.login is LoginUiState.Loading ||
            mutableState.value.showAppDataDisclosure
        ) {
            return
        }
        pendingOnboardingAction = action
        loginStartJob?.cancel()
        loginStartJob = viewModelScope.launch {
            try {
                if (!repository.hasAppDataDisclosureConsent()) {
                    mutableState.update { it.copy(showAppDataDisclosure = true) }
                    return@launch
                }
                startOnboardingAction(action)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = error.toUiMessage()) }
            }
        }
    }

    fun retryLogin() {
        startOnboardingAction(
            pendingOnboardingAction ?: OnboardingAction.TELEGRAM_LOGIN,
        )
    }

    private fun startOnboardingAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.DEVICE_TRIAL -> startDeviceTrial()
            OnboardingAction.TELEGRAM_LOGIN -> startLogin(
                accountActivationSupported = false,
                activateLteTrialAfterLogin = false,
            )
            OnboardingAction.TELEGRAM_LTE_TRIAL -> startLogin(
                accountActivationSupported = false,
                activateLteTrialAfterLogin = true,
            )
            OnboardingAction.WEBSITE_LOGIN -> startLogin(
                accountActivationSupported = true,
                activateLteTrialAfterLogin = false,
            )
        }
    }

    private fun startDeviceTrial() {
        loginPollJob?.cancel()
        loginPollJob = viewModelScope.launch {
            mutableState.update { it.copy(login = LoginUiState.Loading, message = null) }
            try {
                val account = repository.activateDeviceTrial()
                val profile = reconcileAuthenticatedProfile(
                    account = account,
                    hadCachedProfile = false,
                    forceProfileRefresh = true,
                )
                if (profile != null && settings.automaticServer.value) {
                    selectBestServer(profile)
                }
                mutableState.update { it.copy(login = LoginUiState.Idle) }
                pendingOnboardingAction = null
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(login = LoginUiState.Idle, message = error.toUiMessage())
                }
                pendingOnboardingAction = null
            }
        }
    }

    private fun startMobileTrial() {
        loginPollJob?.cancel()
        loginPollJob = viewModelScope.launch {
            mutableState.update { it.copy(login = LoginUiState.Loading, message = null) }
            try {
                val account = repository.activateMobileTrial()
                val profile = reconcileAuthenticatedProfile(
                    account = account,
                    hadCachedProfile = mutableState.value.profile != null,
                    forceProfileRefresh = true,
                )
                if (profile != null && settings.automaticServer.value) {
                    selectBestServer(profile)
                }
                mutableState.update { it.copy(login = LoginUiState.Idle) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(login = LoginUiState.Idle, message = error.toUiMessage())
                }
            }
        }
    }

    private fun startLogin(
        accountActivationSupported: Boolean,
        activateLteTrialAfterLogin: Boolean,
    ) {
        loginPollJob?.cancel()
        loginPollJob = viewModelScope.launch {
            mutableState.update { it.copy(login = LoginUiState.Loading, message = null) }
            try {
                val challenge = repository.beginLogin(accountActivationSupported)
                val authorization = requireNotNull(
                    AuthorizationChallengePolicy.resolve(challenge),
                ) {
                    "Authentication challenge does not contain a supported authorization target."
                }
                mutableState.update {
                    it.copy(login = LoginUiState.Waiting(challenge, authorization))
                }
                openChallengeAuthorization(authorization)
                pollChallenge(challenge, activateLteTrialAfterLogin)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(login = LoginUiState.Idle, message = error.toUiMessage())
                }
            }
        }
    }

    fun acceptAppDataDisclosure() {
        loginStartJob?.cancel()
        loginStartJob = viewModelScope.launch {
            try {
                repository.acceptAppDataDisclosure()
                mutableState.update { it.copy(showAppDataDisclosure = false) }
                startOnboardingAction(
                    pendingOnboardingAction ?: OnboardingAction.TELEGRAM_LOGIN,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        showAppDataDisclosure = false,
                        message = error.toUiMessage(),
                    )
                }
            }
        }
    }

    fun declineAppDataDisclosure() {
        pendingOnboardingAction = null
        mutableState.update { it.copy(showAppDataDisclosure = false) }
    }

    fun openLoginUriAgain() {
        val waiting = mutableState.value.login as? LoginUiState.Waiting ?: return
        viewModelScope.launch {
            openChallengeAuthorization(waiting.authorization)
        }
    }

    private suspend fun openChallengeAuthorization(authorization: ChallengeAuthorization) {
        when (authorization) {
            is ChallengeAuthorization.AccountActivation -> {
                effectChannel.send(AppEffect.OpenAuthorization(authorization.uri))
            }
            is ChallengeAuthorization.LegacyTelegram -> {
                effectChannel.send(AppEffect.OpenExternal(authorization.uri))
            }
        }
    }

    fun refreshAccount() {
        refreshSubscription(showErrors = true)
        viewModelScope.launch { refreshLevikStatus() }
    }

    private fun calculateLteTraffic(
        state: AppUiState,
        vpn: VpnSnapshot,
    ): TrafficSummary? {
        if (vpn.state !in LTE_TRAFFIC_STATES) return null
        val server = state.profile?.servers?.firstOrNull {
            it.id == state.selectedServerId
        } ?: return null
        if (!server.isMobileServer()) return null
        val account = state.account ?: return null
        val subscriptionId = state.profile?.subscriptionId
            ?: state.selectedSubscriptionId
            ?: return null
        val subscription = account.subscriptions.firstOrNull {
            it.uuid == subscriptionId
        } ?: return null
        val traffic = subscription.components?.mobile?.traffic ?: subscription.traffic
        if (traffic.limitBytes <= 0L) return null
        val sessionBytes = saturatingAdd(
            vpn.downloadedBytes.coerceAtLeast(0L),
            vpn.uploadedBytes.coerceAtLeast(0L),
        )
        return traffic.copy(
            usedBytes = lteTrafficAccumulator.estimateUsedBytes(
                subscriptionId = subscriptionId,
                serverId = server.id,
                serverUsedBytes = traffic.usedBytes,
                sessionBytes = sessionBytes,
            ),
        )
    }

    private fun updateLteTrafficSync(vpn: VpnSnapshot) {
        val state = mutableState.value
        val shouldSync = vpn.state == VpnConnectionState.CONNECTED &&
            state.profile?.servers?.firstOrNull { it.id == state.selectedServerId }
                ?.isMobileServer() == true
        if (!shouldSync) {
            lteTrafficSyncJob?.cancel()
            lteTrafficSyncJob = null
            return
        }
        if (lteTrafficSyncJob?.isActive == true) return
        lteTrafficSyncJob = viewModelScope.launch {
            while (true) {
                delay(LTE_TRAFFIC_SYNC_INTERVAL_MS)
                runCatching { repository.refreshAccount() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                    }
            }
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private suspend fun refreshLevikStatus() {
        runCatching { apiClient.status() }
            .onSuccess { status -> mutableState.update { it.copy(levikStatus = status) } }
    }

    fun setSubscriptionShield(subscriptionId: String, enabled: Boolean) {
        if (mutableState.value.refreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, message = null) }
            try {
                repository.setSubscriptionShield(subscriptionId, enabled)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun openPurchaseFlow() {
        if (!BuildConfig.EXTERNAL_PURCHASES_ENABLED || mutableState.value.purchaseLoading) return
        viewModelScope.launch {
            mutableState.update { it.copy(purchaseLoading = true, message = null) }
            try {
                val catalog = repository.catalog()
                mutableState.update { it.copy(purchaseCatalog = catalog) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(purchaseLoading = false) }
            }
        }
    }

    fun closePurchaseFlow() {
        if (!mutableState.value.purchaseLoading) {
            mutableState.update { it.copy(purchaseCatalog = null) }
        }
    }

    fun purchaseAccess(
        kind: String,
        subscriptionId: String?,
        tariffId: String?,
        months: Int?,
        paymentMethodId: String,
    ) {
        if (!BuildConfig.EXTERNAL_PURCHASES_ENABLED || mutableState.value.purchaseLoading) return
        viewModelScope.launch {
            mutableState.update { it.copy(purchaseLoading = true, message = null) }
            try {
                val order = repository.createOrder(
                    kind,
                    subscriptionId,
                    tariffId,
                    months,
                    paymentMethodId,
                )
                val paymentUrl = requireNotNull(order.paymentUrl) { "Payment URL is unavailable" }
                mutableState.update { it.copy(purchaseCatalog = null) }
                effectChannel.send(AppEffect.OpenExternal(paymentUrl))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(purchaseLoading = false) }
            }
        }
    }

    private fun refreshSubscription(showErrors: Boolean) {
        if (mutableState.value.refreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, message = if (showErrors) null else it.message) }
            try {
                val hadCachedProfile = mutableState.value.profile != null
                val account = repository.refreshAccount()
                val profile = reconcileAuthenticatedProfile(
                    account = account,
                    hadCachedProfile = hadCachedProfile,
                    forceProfileRefresh = true,
                )
                if (profile != null &&
                    settings.automaticServer.value &&
                    vpnController.state.value.state in PINGABLE_STATES
                ) {
                    selectBestServer(profile)
                }
                val selectedSub = account.subscriptions.firstOrNull { it.uuid == settings.selectedSubscriptionId.value }
                    ?: account.subscriptions.firstOrNull()
                if (selectedSub != null && appContext != null) {
                    com.leviknet.vpn.core.notification.SubscriptionNotificationManager.checkAndNotify(
                        appContext,
                        selectedSub,
                        settings,
                    )
                }
                if (showErrors) {
                    mutableState.update { it.copy(message = UiMessage.SUBSCRIPTION_UPDATED) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (showErrors) {
                    mutableState.update { it.copy(message = error.toUiMessage()) }
                }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun connectOrDisconnect() {
        when (mutableState.value.vpn.state) {
            VpnConnectionState.PAUSED -> resumeVpn()
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING,
            -> vpnController.disconnect()
            VpnConnectionState.STOPPING -> Unit
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR,
            VpnConnectionState.LOCKDOWN,
            -> prepareConnection()
        }
    }

    private fun prepareConnection(allowLteWithoutWhitelist: Boolean = false) {
        if (mutableState.value.refreshing) return
        viewModelScope.launch {
            val account = mutableState.value.account
            val cached = repository.cachedTunnel()
                ?.takeIf { cachedProfileIsUsable(it.subscriptionExpiresAt, Instant.now()) }
            val subscription = account?.let {
                activeSubscription(
                    it,
                    preferredSubscriptionId = settings.selectedSubscriptionId.value
                        ?: cached?.subscriptionId,
                )
            }
            if (account != null && subscription == null) {
                mutableState.update { it.copy(message = UiMessage.SUBSCRIPTION_REQUIRED) }
                return@launch
            }
            if (account == null && cached == null) {
                mutableState.update { it.copy(message = UiMessage.SUBSCRIPTION_REQUIRED) }
                return@launch
            }
            mutableState.update { it.copy(refreshing = true, message = null) }
            try {
                val profile = if (subscription == null) {
                    requireNotNull(cached)
                } else {
                    val reusableCached = cached?.takeIf { profile ->
                        profile.subscriptionId == subscription.uuid &&
                            equivalentSubscriptionExpiry(
                                profile.subscriptionExpiresAt,
                                subscription.expireAt,
                            )
                    }
                    val refreshRequested = reusableCached != null &&
                        (
                            profileRefreshPending ||
                                profileRefreshDue(reusableCached.issuedAt, Instant.now())
                            )
                    if (reusableCached != null && !refreshRequested) {
                        reusableCached
                    } else {
                        try {
                            prepareTunnelWithRetry(subscription.uuid).also {
                                profileRefreshPending = false
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            if (reusableCached == null) throw error
                            profileRefreshPending = true
                            mutableState.update {
                                it.copy(message = error.toUiMessage())
                            }
                            reusableCached
                        }
                    }
                }
                val selected = selectServerForProfile(profile)
                mutableState.update {
                    it.copy(profile = profile, selectedServerId = selected)
                }
                val selectedServer = profile.servers.firstOrNull { it.id == selected }
                if (!allowLteWithoutWhitelist &&
                    selectedServer?.isMobileServer() == true &&
                    mutableState.value.whitelistMode == WhitelistMode.INACTIVE
                ) {
                    pendingLteAction = PendingLteAction.Connect
                    mutableState.update { it.copy(showLteWhitelistWarning = true) }
                    return@launch
                }
                connectionPending = true
                if (vpnController.hasDisclosureConsent()) {
                    effectChannel.send(AppEffect.RequestVpnPermission)
                } else {
                    mutableState.update { it.copy(showVpnDisclosure = true) }
                }
            } catch (error: Throwable) {
                mutableState.update { it.copy(message = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun acceptVpnDisclosure() {
        viewModelScope.launch {
            try {
                vpnController.acceptDisclosure()
                mutableState.update { it.copy(showVpnDisclosure = false) }
                if (connectionPending) {
                    effectChannel.send(AppEffect.RequestVpnPermission)
                }
            } catch (error: Throwable) {
                connectionPending = false
                mutableState.update {
                    it.copy(
                        showVpnDisclosure = false,
                        message = error.toUiMessage(),
                    )
                }
            }
        }
    }

    fun declineVpnDisclosure() {
        connectionPending = false
        mutableState.update { it.copy(showVpnDisclosure = false) }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (!connectionPending) return
        if (!granted) {
            connectionPending = false
            mutableState.update { it.copy(message = UiMessage.VPN_PERMISSION_DENIED) }
            return
        }
        viewModelScope.launch { effectChannel.send(AppEffect.RequestNotificationPermission) }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!connectionPending) return
        connectionPending = false
        if (!granted) {
            mutableState.update { it.copy(message = UiMessage.NOTIFICATION_PERMISSION_DENIED) }
            return
        }
        viewModelScope.launch {
            runCatching { vpnController.connect() }
                .onFailure { error ->
                    mutableState.update { it.copy(message = error.toUiMessage()) }
                }
        }
    }

    fun selectServer(serverId: String) {
        val profile = mutableState.value.profile ?: return
        val server = profile.servers.firstOrNull { it.id == serverId } ?: return
        if (server.isMobileServer() &&
            mutableState.value.whitelistMode == WhitelistMode.INACTIVE &&
            mutableState.value.vpn.state in ACTIVE_TUNNEL_STATES
        ) {
            pendingLteAction = PendingLteAction.SelectServer(serverId)
            mutableState.update { it.copy(showLteWhitelistWarning = true) }
            return
        }
        selectServerNow(serverId)
    }

    private fun selectServerNow(serverId: String) {
        viewModelScope.launch {
            runCatching {
                settings.setAutomaticServer(false)
                repository.selectServer(serverId)
            }
                .onSuccess {
                    mutableState.update { current ->
                        current.copy(
                            selectedServerId = serverId,
                            pingMs = current.serverPings[serverId],
                        )
                    }
                    if (mutableState.value.vpn.state in ACTIVE_TUNNEL_STATES) {
                        AppLogger.i("AppViewModel", "Seamlessly switching VPN server")
                        vpnController.switchServer(serverId)
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(message = error.toUiMessage()) }
                }
        }
    }

    fun confirmLteWhitelistWarning() {
        val action = pendingLteAction ?: return
        pendingLteAction = null
        mutableState.update { it.copy(showLteWhitelistWarning = false) }
        when (action) {
            PendingLteAction.Connect -> prepareConnection(allowLteWithoutWhitelist = true)
            is PendingLteAction.SelectServer -> selectServerNow(action.serverId)
        }
    }

    fun dismissLteWhitelistWarning() {
        pendingLteAction = null
        mutableState.update { it.copy(showLteWhitelistWarning = false) }
    }

    fun selectAutomaticServer() {
        val profile = mutableState.value.profile ?: return
        if (mutableState.value.refreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, message = null) }
            try {
                settings.setAutomaticServer(true)
                val selected = selectBestServer(profile)
                mutableState.update { it.copy(selectedServerId = selected) }
                if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
                    vpnController.switchServer(selected)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun selectSubscription(subscriptionId: String) {
        if (mutableState.value.vpn.state !in setOf(
                VpnConnectionState.DISCONNECTED,
                VpnConnectionState.ERROR,
            ) || mutableState.value.refreshing
        ) {
            return
        }
        val subscription = mutableState.value.account?.subscriptions
            ?.firstOrNull { it.uuid == subscriptionId && it.isActiveAt(Instant.now()) }
            ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, message = null) }
            try {
                val profile = prepareTunnelWithRetry(subscription.uuid)
                ensureSelectedSubscription(subscription.uuid)
                profileRefreshPending = false
                val selected = selectServerForProfile(profile)
                mutableState.update {
                    it.copy(profile = profile, selectedServerId = selected)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun setRoutingPreset(preset: RoutingPreset) {
        settings.setRoutingPreset(preset)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setBypassRussianTraffic(enabled: Boolean) {
        if (mutableState.value.vpn.state in setOf(
                VpnConnectionState.CONNECTING,
                VpnConnectionState.RECONNECTING,
                VpnConnectionState.STOPPING,
            )
        ) {
            return
        }
        runCatching { settings.setBypassRussianTraffic(enabled) }
            .onSuccess {
                if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
                    vpnController.reconfigure()
                }
            }
            .onFailure {
                mutableState.update { state -> state.copy(message = UiMessage.GENERIC_ERROR) }
            }
    }

    fun setAntiDpiPreset(preset: AntiDpiPreset) {
        settings.setAntiDpiPreset(preset)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setAntiDpiCustomParams(packets: String, length: String, interval: String) {
        settings.setAntiDpiCustomParams(packets, length, interval)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setAntiDpiEnabled(enabled: Boolean) {
        settings.setAntiDpiEnabled(enabled)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setAutoHealingEnabled(enabled: Boolean) {
        settings.setAutoHealingEnabled(enabled)
    }

    fun setKillSwitchEnabled(enabled: Boolean) {
        settings.setKillSwitchEnabled(enabled)
    }

    fun setAutoConnectUntrustedWifi(enabled: Boolean) {
        if (!enabled) {
            settings.setAutoConnectUntrustedWifi(false)
            return
        }
        pendingWifiAutoConnect = true
        viewModelScope.launch {
            effectChannel.send(AppEffect.RequestLocationPermission)
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!pendingWifiAutoConnect) return
        pendingWifiAutoConnect = false
        if (granted) {
            settings.setAutoConnectUntrustedWifi(true)
        } else {
            mutableState.update { it.copy(message = UiMessage.LOCATION_PERMISSION_DENIED) }
        }
    }

    fun addTrustedWifi(ssid: String) {
        settings.addTrustedWifiSsid(ssid)
    }

    fun removeTrustedWifi(ssid: String) {
        settings.removeTrustedWifiSsid(ssid)
    }

    fun setUseDoh(enabled: Boolean) {
        settings.setUseDoh(enabled)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setCustomDohUrl(url: String) {
        settings.setCustomDohUrl(url)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun requestLogout() {
        mutableState.update { it.copy(showLogoutConfirmation = true) }
    }

    fun cancelLogout() {
        mutableState.update { it.copy(showLogoutConfirmation = false) }
    }

    fun confirmLogout() {
        viewModelScope.launch {
            mutableState.update {
                it.copy(showLogoutConfirmation = false, refreshing = true, message = null)
            }
            vpnController.disconnect()
            loginStartJob?.cancel()
            loginPollJob?.cancel()
            try {
                repository.logout()
                settings.setSelectedSubscriptionId(null)
                mutableState.update {
                    it.copy(
                        account = null,
                        profile = null,
                        selectedServerId = null,
                        selectedSubscriptionId = null,
                        login = LoginUiState.Idle,
                        tab = AppTab.HOME,
                    )
                }
            } catch (error: Throwable) {
                mutableState.update { it.copy(message = error.toUiMessage()) }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun openSupport() {
        viewModelScope.launch {
            effectChannel.send(AppEffect.OpenExternal(SUPPORT_URL))
        }
    }

    fun openFreeProxyBot() {
        viewModelScope.launch {
            val link = repository.fetchFreeProxyLink()
            effectChannel.send(AppEffect.OpenExternal(link))
        }
    }

    fun openFreeProxy() = openFreeProxyBot()

    fun openPrivacyPolicy() {
        viewModelScope.launch {
            effectChannel.send(AppEffect.OpenExternal(PRIVACY_POLICY_URL))
        }
    }

    fun openAccountDeletion() {
        viewModelScope.launch {
            effectChannel.send(AppEffect.OpenExternal(ACCOUNT_DELETION_URL))
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private suspend fun pollChallenge(
        challenge: AuthChallengeResponse,
        activateLteTrialAfterLogin: Boolean,
    ) {
        val expiresAt = Instant.parse(challenge.expiresAt)
        var interval = challenge.pollIntervalSeconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)
        while (Instant.now().isBefore(expiresAt)) {
            delay(interval * 1000L)
            when (val result = repository.pollLogin(challenge.loginToken)) {
                is LoginPollResult.Pending -> {
                    interval = result.pollIntervalSeconds.coerceIn(
                        MIN_POLL_SECONDS,
                        MAX_POLL_SECONDS,
                    )
                }
                LoginPollResult.Authenticated -> {
                    val hadCachedProfile = mutableState.value.profile != null
                    val account = if (activateLteTrialAfterLogin) {
                        repository.activateMobileTrial()
                    } else {
                        repository.account.value
                    }
                    account?.let {
                        reconcileAuthenticatedProfile(
                            account = it,
                            hadCachedProfile = hadCachedProfile,
                            forceProfileRefresh = true,
                        )
                    }
                    mutableState.update { it.copy(login = LoginUiState.Idle, message = null) }
                    pendingOnboardingAction = null
                    return
                }
                LoginPollResult.Expired -> {
                    mutableState.update { it.copy(login = LoginUiState.Expired) }
                    return
                }
                LoginPollResult.Denied -> {
                    mutableState.update {
                        it.copy(login = LoginUiState.Idle, message = UiMessage.LOGIN_DENIED)
                    }
                    return
                }
            }
        }
        mutableState.update { it.copy(login = LoginUiState.Expired) }
    }

    private suspend fun syncCachedProfile(): PreparedTunnelProfile? {
        val profile = repository.cachedTunnel()
            ?.takeIf { cachedProfileIsUsable(it.subscriptionExpiresAt, Instant.now()) }
        val selected = repository.selectedServerId()
            ?.takeIf { id -> profile?.servers?.any { it.id == id } == true }
            ?: profile?.servers?.firstOrNull()?.id
        mutableState.update {
            it.copy(
                profile = profile,
                selectedServerId = selected,
            )
        }
        return profile
    }

    private suspend fun reconcileAuthenticatedProfile(
        account: MobileAccountResponse,
        hadCachedProfile: Boolean,
        forceProfileRefresh: Boolean,
    ): PreparedTunnelProfile? {
        val cached = repository.cachedTunnel()
        val matchingSubscription = activeSubscription(
            account = account,
            preferredSubscriptionId = settings.selectedSubscriptionId.value
                ?: cached?.subscriptionId,
        )
        if (matchingSubscription != null) {
            ensureSelectedSubscription(matchingSubscription.uuid)
        }
        val refreshRequired = matchingSubscription != null && (
            cached == null ||
                cached.subscriptionId != matchingSubscription.uuid ||
                forceProfileRefresh ||
                profileRefreshDue(cached.issuedAt, Instant.now()) ||
                !equivalentSubscriptionExpiry(
                    cached.subscriptionExpiresAt,
                    matchingSubscription.expireAt,
                )
            )
        if (refreshRequired) {
            if (vpnController.state.value.state in ACTIVE_TUNNEL_STATES) {
                if (cached == null) {
                    vpnController.disconnect()
                }
                profileRefreshPending = true
                return syncCachedProfile()
            }
            return try {
                val refreshed = prepareTunnelWithRetry(requireNotNull(matchingSubscription).uuid)
                profileRefreshPending = false
                syncCachedProfile()
                refreshed
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                profileRefreshPending = true
                syncCachedProfile()
                throw error
            }
        }

        val profile = syncCachedProfile()
        if (shouldDisconnectAfterProfileRemoval(
                hadCachedProfile = hadCachedProfile,
                hasCachedProfile = profile != null,
                vpnState = vpnController.state.value.state,
            )
        ) {
            vpnController.disconnect()
        }
        return profile
    }

    private suspend fun prepareTunnelWithRetry(
        subscriptionId: String,
    ): PreparedTunnelProfile {
        var failedAttempts = 0
        while (true) {
            try {
                return repository.prepareTunnel(subscriptionId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val retryDelay = profileLoadRetryDelayMillis(error, failedAttempts)
                    ?: throw error
                failedAttempts += 1
                AppLogger.w(
                    "AppViewModel",
                    "Tunnel profile load failed temporarily; retrying (attempt $failedAttempts)",
                )
                delay(retryDelay)
            }
        }
    }

    private suspend fun selectServerForProfile(profile: PreparedTunnelProfile): String {
        if (settings.automaticServer.value) return selectBestServer(profile)
        val selected = repository.selectedServerId()
            ?.takeIf { id -> profile.servers.any { it.id == id } }
            ?: profile.servers.firstOrNull(TunnelServer::isEligibleForAutomaticSelection)?.id
            ?: error("No server is eligible for automatic selection")
        repository.selectServer(selected)
        viewModelScope.launch {
            runCatching {
                val measured = measureServers(profile.servers)
                mutableState.update { current ->
                    if (current.profile === profile) {
                        current.copy(
                            pingMs = current.selectedServerId?.let(measured::get),
                            serverPings = measured,
                            pingingServers = false,
                        )
                    } else current
                }
            }
        }
        return selected
    }

    private suspend fun selectBestServer(profile: PreparedTunnelProfile): String {
        val measured = measureServers(profile.servers)
        val eligibleServerIds = profile.servers
            .filter(TunnelServer::isEligibleForAutomaticSelection)
            .mapTo(mutableSetOf(), TunnelServer::id)
        require(eligibleServerIds.isNotEmpty()) {
            "No server is eligible for automatic selection"
        }
        val regularServerIds = profile.servers
            .filter { it.id in eligibleServerIds && !it.isMobileServer() }
            .mapTo(mutableSetOf(), TunnelServer::id)
        val preferredServerIds = regularServerIds.ifEmpty { eligibleServerIds }
        val selected = measured.entries
            .filter { it.key in preferredServerIds && it.value != null }
            .minByOrNull { requireNotNull(it.value) }
            ?.key
            ?: repository.selectedServerId()
                ?.takeIf(preferredServerIds::contains)
            ?: profile.servers.first { it.id in preferredServerIds }.id
        repository.selectServer(selected)
        mutableState.update { state ->
            state.copy(
                selectedServerId = selected,
                pingMs = measured[selected],
                serverPings = measured,
                pingingServers = false,
                message = if (measured.values.none { it != null }) {
                    UiMessage.SERVER_PING_UNAVAILABLE
                } else {
                    state.message
                },
            )
        }
        return selected
    }

    private suspend fun measureServers(servers: List<TunnelServer>): Map<String, Long?> =
        serverPingMutex.withLock {
            mutableState.update { it.copy(pingingServers = true) }
            supervisorScope {
                servers.map { server ->
                    async(Dispatchers.IO) {
                        server.id to ServerPinger.measure(server.outbound)
                    }
                }.awaitAll().toMap()
            }
        }

    private fun ensureSelectedSubscription(subscriptionId: String) {
        if (settings.selectedSubscriptionId.value != subscriptionId) {
            settings.setSelectedSubscriptionId(subscriptionId)
        }
    }

    private suspend fun serverPingLoop() {
        state.map { it.tab in setOf(AppTab.HOME, AppTab.SERVERS) }
            .distinctUntilChanged()
            .collectLatest { tabActive ->
                if (!tabActive) return@collectLatest
                while (true) {
                    val snapshot = mutableState.value
                    val profile = snapshot.profile
                    if (profile != null && !snapshot.refreshing) {
                        val measured = measureServers(profile.servers)
                        val eligibleServerIds = profile.servers
                            .filter(TunnelServer::isEligibleForAutomaticSelection)
                            .mapTo(mutableSetOf(), TunnelServer::id)
                        val regularServerIds = profile.servers
                            .filter { it.id in eligibleServerIds && !it.isMobileServer() }
                            .mapTo(mutableSetOf(), TunnelServer::id)
                        val preferredServerIds = regularServerIds.ifEmpty { eligibleServerIds }
                        val bestServerId = measured.entries
                            .filter { it.key in preferredServerIds && it.value != null }
                            .minByOrNull { requireNotNull(it.value) }
                            ?.key
                        if (snapshot.automaticServer &&
                            bestServerId != null &&
                            snapshot.vpn.state in PINGABLE_STATES
                        ) {
                            repository.selectServer(bestServerId)
                        }
                        mutableState.update { current ->
                            if (current.profile === profile) {
                                val selected = if (current.automaticServer && bestServerId != null &&
                                    current.vpn.state in PINGABLE_STATES
                                ) {
                                    bestServerId
                                } else {
                                    current.selectedServerId
                                }
                                current.copy(
                                    selectedServerId = selected,
                                    pingMs = selected?.let(measured::get),
                                    serverPings = measured,
                                    pingingServers = false,
                                )
                            } else {
                                current.copy(pingingServers = false)
                            }
                        }
                    }
                    delay(SERVER_PING_INTERVAL_MS)
                }
            }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        settings.setSplitTunnelMode(mode)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun toggleSplitTunnelPackage(packageName: String) {
        val current = settings.splitTunnelPackages.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        settings.setSplitTunnelPackages(current)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setDnsProvider(provider: DnsProvider) {
        settings.setDnsProvider(provider)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setCustomDnsIpv4(ip: String) {
        settings.setCustomDnsIpv4(ip)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.setThemeMode(mode)
    }

    fun setUseDynamicColors(enabled: Boolean) {
        settings.setUseDynamicColors(enabled)
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        settings.setAutoConnectOnBoot(enabled)
    }

    fun setAutoFallbackServer(enabled: Boolean) {
        settings.setAutoFallbackServer(enabled)
    }

    fun toggleFavoriteServer(serverId: String) {
        settings.toggleFavoriteServer(serverId)
    }

    fun addCustomDirectDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
        if (trimmed.isNotBlank()) {
            val current = settings.customDirectDomains.value.toMutableSet()
            current.add(trimmed)
            settings.setCustomDirectDomains(current)
            if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
                vpnController.reconfigure()
            }
        }
    }

    fun removeCustomDirectDomain(domain: String) {
        val current = settings.customDirectDomains.value.toMutableSet()
        current.remove(domain)
        settings.setCustomDirectDomains(current)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun addCustomProxyDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
        if (trimmed.isNotBlank()) {
            val current = settings.customProxyDomains.value.toMutableSet()
            current.add(trimmed)
            settings.setCustomProxyDomains(current)
            if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
                vpnController.reconfigure()
            }
        }
    }

    fun removeCustomProxyDomain(domain: String) {
        val current = settings.customProxyDomains.value.toMutableSet()
        current.remove(domain)
        settings.setCustomProxyDomains(current)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setServerSearchQuery(query: String) {
        mutableState.update { it.copy(serverSearchQuery = query) }
    }

    fun setServerFilter(filter: ServerFilterType) {
        mutableState.update { it.copy(serverFilter = filter) }
    }

    fun loadInstalledApps(packageManager: PackageManager) {
        viewModelScope.launch(Dispatchers.IO) {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            val apps = resolveInfos.mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == "com.leviknet.vpn") return@mapNotNull null
                val name = info.loadLabel(packageManager).toString()
                val icon = info.loadIcon(packageManager)
                InstalledAppItem(packageName = pkg, label = name, icon = icon)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            mutableState.update { it.copy(installedApps = apps) }
        }
    }

    private suspend fun capturePerAppTrafficBaseline() {
        val baseline = perAppBaselineMutex.withLock {
            val snapshot = collectUidTraffic() ?: return@withLock emptyMap()
            snapshot
        }
        if (baseline.isNotEmpty()) {
            perAppTrafficBaseline = baseline
        }
    }

    /** uid -> (rx, tx) totals for every launcher-visible package. */
    private fun collectUidTraffic(): Map<Int, Pair<Long, Long>>? {
        val pm = appContext?.packageManager ?: return null
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            buildMap {
                for (info in resolveInfos) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == "com.leviknet.vpn") continue
                    val uid = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getPackageUid(pkg, 0)
                        }
                    }.getOrNull() ?: continue
                    put(uid, Pair(
                        TrafficStats.getUidRxBytes(uid).coerceAtLeast(0),
                        TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
                    ))
                }
            }
        } catch (error: Throwable) {
            AppLogger.w("AppViewModel", "Failed to capture per-app baseline: ${error.message}")
            null
        }
    }

    fun loadPerAppTraffic(packageManager: PackageManager, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val baseline = perAppTrafficBaseline
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            val allApps = resolveInfos.mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == "com.leviknet.vpn") return@mapNotNull null
                val uid = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageUid(pkg, 0)
                    }
                }.getOrNull() ?: return@mapNotNull null

                val rxTotal = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                val txTotal = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                val base = baseline[uid]
                val rx = if (base != null) (rxTotal - base.first).coerceAtLeast(0) else rxTotal
                val tx = if (base != null) (txTotal - base.second).coerceAtLeast(0) else txTotal

                val name = info.loadLabel(packageManager).toString()
                val icon = info.loadIcon(packageManager)
                AppTrafficUsage(packageName = pkg, label = name, icon = icon, rxBytes = rx, txBytes = tx)
            }

            val listWithTraffic = allApps.filter { it.rxBytes > 0 || it.txBytes > 0 }
                .sortedByDescending { it.rxBytes + it.txBytes }
            val list = if (listWithTraffic.isNotEmpty()) {
                listWithTraffic
            } else {
                allApps.take(10)
            }
            mutableState.update { it.copy(perAppTraffic = list) }
        }
    }

    fun resetPerAppTrafficBaseline(packageManager: PackageManager, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            perAppTrafficBaseline = emptyMap()
            capturePerAppTrafficBaseline()
            mutableState.update { it.copy(perAppTraffic = emptyList()) }
        }
    }

    fun revokeDevice(subscriptionId: String, deviceId: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true) }
            try {
                repository.revokeDevice(subscriptionId, deviceId)
                mutableState.update { it.copy(refreshing = false, message = UiMessage.DEVICE_REVOKED_SUCCESS) }
            } catch (e: Exception) {
                AppLogger.e("AppViewModel", "Failed to revoke device: ${e.message}", e)
                mutableState.update { it.copy(refreshing = false, message = UiMessage.DEVICE_REVOKE_FAILED) }
            }
        }
    }

    fun clearTrafficHistory() {
        trafficHistoryStore?.clearHistory()
        mutableState.update { it.copy(message = UiMessage.TRAFFIC_HISTORY_CLEARED) }
    }

    fun exportTrafficHistory(shareTitle: String) {
        val csv = trafficHistoryStore?.exportHistoryCsv() ?: return
        shareText(shareTitle, csv)
        mutableState.update { it.copy(message = UiMessage.TRAFFIC_HISTORY_EXPORTED) }
    }

    fun runDiagnostics() {
        if (mutableState.value.runningDiagnostics) return
        viewModelScope.launch {
            mutableState.update { it.copy(runningDiagnostics = true, diagnosticReport = null) }
            val report = NetworkDiagnostics.runDiagnostics(
                vpnSnapshot = vpnController.state.value,
                apiClient = apiClient,
                sendTelemetry = settings.anonymousTelemetryEnabled.value,
            )
            mutableState.update { it.copy(runningDiagnostics = false, diagnosticReport = report) }
        }
    }

    fun dismissDiagnostics() {
        mutableState.update { it.copy(diagnosticReport = null, runningDiagnostics = false) }
    }

    fun pauseVpn(minutes: Int) {
        vpnController.pause(minutes)
    }

    fun resumeVpn() {
        vpnController.resume()
    }

    fun checkForUpdates() {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        viewModelScope.launch {
            updateManager.checkForUpdates(silent = false)
        }
    }

    fun onAppForegrounded() {
        refreshWhitelistStatus()
        if (!BuildConfig.SELF_UPDATE_ENABLED || updateCheckJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            updateManager.checkForUpdates(silent = true)
        }
    }

    private fun refreshWhitelistStatus() {
        val detector = whitelistDetector ?: return
        if (whitelistDetectionJob?.isActive == true) return
        whitelistDetectionJob = viewModelScope.launch {
            val mode = try {
                detector.detect()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AppLogger.w("AppViewModel", "Allow-list detection failed")
                WhitelistMode.UNKNOWN
            }
            mutableState.update { it.copy(whitelistMode = mode) }
        }
    }

    fun downloadAndInstallUpdate(update: AppUpdateDto) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        viewModelScope.launch {
            updateManager.downloadAndInstall(update)
        }
    }

    fun dismissUpdateDialog() {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        updateManager.dismiss()
    }

    fun setAnonymousTelemetryEnabled(enabled: Boolean) {
        settings.setAnonymousTelemetryEnabled(enabled)
        appContext?.let { CensorshipRadarWorker.configure(it, enabled) }
    }

    fun shareDiagnosticReportAsNote() {
        val report = mutableState.value.diagnosticReport ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(isSharingNote = true) }
            try {
                val noteUrl = NetworkDiagnostics.createEncryptedSupportNote(report.toFormattedString(), apiClient)
                mutableState.update { it.copy(supportNoteUrl = noteUrl, isSharingNote = false) }
            } catch (e: Exception) {
                AppLogger.e("AppViewModel", "Failed to share note", e)
                mutableState.update { it.copy(isSharingNote = false, message = UiMessage.GENERIC_ERROR) }
            }
        }
    }

    fun clearSupportNoteUrl() {
        mutableState.update { it.copy(supportNoteUrl = null) }
    }

    fun openExternalUrl(url: String) {
        viewModelScope.launch {
            effectChannel.send(AppEffect.OpenExternal(url))
        }
    }

    fun shareText(title: String, text: String) {
        viewModelScope.launch {
            effectChannel.send(AppEffect.ShareText(title = title, text = text))
        }
    }

    fun shareReferralLink(link: String) = shareText("Levik VPN", link)

    fun requestIgnoreBatteryOptimization() {
        viewModelScope.launch {
            effectChannel.send(AppEffect.RequestBatteryOptimization)
        }
    }

    fun handleDeepLink(uri: android.net.Uri) {
        when (DeepLinkRouter.route(uri.toString())) {
            DeepLinkDestination.ACTIVATION -> {
                // Login completion continues through the already-running challenge poll.
                AppLogger.i("AppViewModel", "Accepted activation callback")
            }
            null -> AppLogger.w("AppViewModel", "Rejected unsupported deep link")
        }
    }

    fun getLogs(): List<LogEntry> = AppLogger.getLogs()

    fun getFormattedLogs(): String = AppLogger.getFormattedLogs()

    fun clearLogs() {
        AppLogger.clear()
    }

    private fun activeSubscription(
        account: MobileAccountResponse,
        preferredSubscriptionId: String?,
    ): SubscriptionSummary? {
        val active = account.subscriptions.filter { it.isActiveAt(Instant.now()) }
        return active.firstOrNull { it.uuid == preferredSubscriptionId } ?: active.firstOrNull()
    }

    private fun Throwable.toUiMessage(): UiMessage = when (this) {
        is ApiException.AttestationUnavailable -> UiMessage.ATTESTATION_UNAVAILABLE
        is ApiException.Unauthorized -> UiMessage.SESSION_EXPIRED
        is ApiException.Rejected -> when (code) {
            "device_limit_reached" -> UiMessage.DEVICE_LIMIT_REACHED
            "subscription_not_found" -> UiMessage.SUBSCRIPTION_REQUIRED
            "profile_rate_limited", "rate_limited" -> UiMessage.RATE_LIMITED
            "profile_upstream_unavailable", "profile_unavailable" -> UiMessage.PROFILE_UNAVAILABLE
            "login_denied" -> UiMessage.LOGIN_DENIED
            else -> UiMessage.GENERIC_ERROR
        }
        is IllegalArgumentException -> UiMessage.PROFILE_UNAVAILABLE
        else -> UiMessage.GENERIC_ERROR
    }

    companion object {
        private const val MIN_POLL_SECONDS = 2
        private const val MAX_POLL_SECONDS = 10
        private const val SERVER_PING_INTERVAL_MS = 30_000L
        private const val LTE_TRAFFIC_SYNC_INTERVAL_MS = 15_000L
        private val PINGABLE_STATES = setOf(
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR,
        )
        private const val SUPPORT_URL = "https://t.me/leviksupportbot"
        private const val FREE_PROXY_BOT_URL = "https://t.me/levikvpnbot"
        private const val ACCOUNT_DELETION_URL = "https://leviknet.com/account/delete"
        private const val PRIVACY_POLICY_URL = "https://leviknet.com/legal/privacy"
        private val ACTIVE_TUNNEL_STATES = setOf(
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING,
        )
        private val LTE_TRAFFIC_STATES = setOf(
            VpnConnectionState.CONNECTED,
            VpnConnectionState.RECONNECTING,
            VpnConnectionState.PAUSED,
        )

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java))
                    return AppViewModel(
                        repository = container.repository,
                        vpnController = container.vpnController,
                        settings = container.settings,
                        apiClient = container.apiClient,
                        updateManager = container.updateManager,
                        trafficHistoryStore = container.trafficHistoryStore,
                        appContext = container.appContext,
                    ) as T
                }
            }
    }
}

private val PROFILE_LOAD_RETRY_DELAYS_MS = longArrayOf(400L, 1_200L, 2_400L)

internal fun profileLoadRetryDelayMillis(
    error: Throwable,
    failedAttempts: Int,
): Long? {
    if (failedAttempts !in PROFILE_LOAD_RETRY_DELAYS_MS.indices) return null
    val retryable = error is ApiException.Network ||
        error is ApiException.Rejected && error.retryable
    return PROFILE_LOAD_RETRY_DELAYS_MS[failedAttempts].takeIf { retryable }
}

internal fun cachedProfileIsUsable(
    subscriptionExpiresAt: String?,
    now: Instant,
): Boolean =
    subscriptionExpiresAt?.let { value ->
        runCatching { Instant.parse(value).isAfter(now) }.getOrDefault(false)
    } ?: true

internal fun profileRefreshDue(
    issuedAt: String,
    now: Instant,
    refreshIntervalSeconds: Long = PROFILE_REFRESH_INTERVAL_SECONDS,
): Boolean {
    require(refreshIntervalSeconds > 0)
    val issued = runCatching { Instant.parse(issuedAt) }.getOrNull() ?: return true
    return !issued.isAfter(now.minusSeconds(refreshIntervalSeconds))
}

internal fun shouldDisconnectAfterProfileRemoval(
    hadCachedProfile: Boolean,
    hasCachedProfile: Boolean,
    vpnState: VpnConnectionState,
): Boolean =
    hadCachedProfile &&
        !hasCachedProfile &&
        vpnState in setOf(
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.RECONNECTING,
        )

internal fun equivalentSubscriptionExpiry(
    cachedValue: String?,
    accountValue: String?,
): Boolean {
    if (cachedValue == null || accountValue == null) {
        return cachedValue == accountValue
    }
    return runCatching {
        Instant.parse(cachedValue) == Instant.parse(accountValue)
    }.getOrDefault(false)
}

private const val PROFILE_REFRESH_INTERVAL_SECONDS = 60 * 60L

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

data class AppTrafficUsage(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val rxBytes: Long,
    val txBytes: Long,
)

data class SpeedSample(
    val downloadBps: Long,
    val uploadBps: Long,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class ServerFilterType {
    ALL,
    REGULAR,
    MOBILE,
    FAVORITES,
    FASTEST,
}

data class AppUiState(
    val session: SessionStatus = SessionStatus.Loading,
    val account: MobileAccountResponse? = null,
    val profile: PreparedTunnelProfile? = null,
    val selectedServerId: String? = null,
    val selectedSubscriptionId: String? = null,
    val vpn: VpnSnapshot = VpnSnapshot(),
    val lteTraffic: TrafficSummary? = null,
    val pingMs: Long? = null,
    val serverPings: Map<String, Long?> = emptyMap(),
    val pingingServers: Boolean = false,
    val login: LoginUiState = LoginUiState.Idle,
    val tab: AppTab = AppTab.HOME,
    val refreshing: Boolean = false,
    val showAppDataDisclosure: Boolean = false,
    val showVpnDisclosure: Boolean = false,
    val showLogoutConfirmation: Boolean = false,
    val showLteWhitelistWarning: Boolean = false,
    val whitelistMode: WhitelistMode = WhitelistMode.UNKNOWN,
    val routingPreset: RoutingPreset = RoutingPreset.BYPASS_RU,
    val bypassRussianTraffic: Boolean = true,
    val antiDpiPreset: AntiDpiPreset = AntiDpiPreset.OFF,
    val antiDpiPackets: String = "tlshello",
    val antiDpiLength: String = "100-200",
    val antiDpiInterval: String = "10-20",
    val antiDpiEnabled: Boolean = false,
    val autoHealingEnabled: Boolean = true,
    val killSwitchEnabled: Boolean = false,
    val autoConnectUntrustedWifi: Boolean = false,
    val trustedWifiSsids: Set<String> = emptySet(),
    val useDoh: Boolean = true,
    val customDohUrl: String = "",
    val automaticServer: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val splitTunnelPackages: Set<String> = emptySet(),
    val installedApps: List<InstalledAppItem> = emptyList(),
    val perAppTraffic: List<AppTrafficUsage> = emptyList(),
    val liveSpeedHistory: List<SpeedSample> = emptyList(),
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val customDnsIpv4: String = "1.1.1.1",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColors: Boolean = false,
    val autoConnectOnBoot: Boolean = false,
    val autoFallbackServer: Boolean = true,
    val favoriteServerIds: Set<String> = emptySet(),
    val customDirectDomains: Set<String> = emptySet(),
    val customProxyDomains: Set<String> = emptySet(),
    val anonymousTelemetryEnabled: Boolean = false,
    val serverSearchQuery: String = "",
    val serverFilter: ServerFilterType = ServerFilterType.ALL,
    val trafficHistory: List<DailyTraffic> = emptyList(),
    val diagnosticReport: DiagnosticReport? = null,
    val runningDiagnostics: Boolean = false,
    val updateState: UpdateState = UpdateState.Idle,
    val supportNoteUrl: String? = null,
    val levikStatus: LevikStatusSnapshot? = null,
    val purchaseCatalog: CatalogResponse? = null,
    val purchaseLoading: Boolean = false,
    val isSharingNote: Boolean = false,
    val message: UiMessage? = null,
)

enum class AppTab {
    HOME,
    SERVERS,
    STATS,
    PROFILE,
}

private enum class OnboardingAction {
    DEVICE_TRIAL,
    TELEGRAM_LOGIN,
    TELEGRAM_LTE_TRIAL,
    WEBSITE_LOGIN,
}

private sealed interface PendingLteAction {
    data object Connect : PendingLteAction
    data class SelectServer(val serverId: String) : PendingLteAction
}

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data class Waiting(
        val challenge: AuthChallengeResponse,
        val authorization: ChallengeAuthorization,
    ) : LoginUiState
    data object Expired : LoginUiState
}

enum class UiMessage {
    GENERIC_ERROR,
    SESSION_EXPIRED,
    SUBSCRIPTION_REQUIRED,
    PROFILE_UNAVAILABLE,
    DEVICE_LIMIT_REACHED,
    RATE_LIMITED,
    VPN_PERMISSION_DENIED,
    NOTIFICATION_PERMISSION_DENIED,
    LOCATION_PERMISSION_DENIED,
    LOGIN_DENIED,
    ATTESTATION_UNAVAILABLE,
    SUBSCRIPTION_UPDATED,
    SERVER_PING_UNAVAILABLE,
    DEVICE_REVOKED_SUCCESS,
    DEVICE_REVOKE_FAILED,
    TRAFFIC_HISTORY_CLEARED,
    TRAFFIC_HISTORY_EXPORTED,
}

sealed interface AppEffect {
    data class OpenAuthorization(val uri: String) : AppEffect
    data class OpenExternal(val uri: String) : AppEffect
    data class ShareText(val title: String, val text: String) : AppEffect
    data object RequestBatteryOptimization : AppEffect
    data object RequestVpnPermission : AppEffect
    data object RequestNotificationPermission : AppEffect
    data object RequestLocationPermission : AppEffect
}
