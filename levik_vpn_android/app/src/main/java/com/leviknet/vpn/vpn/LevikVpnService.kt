package com.leviknet.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.notification.AppIconArtwork
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.WhitelistMode
import com.leviknet.vpn.core.platform.FavoriteNodes
import com.leviknet.vpn.core.security.SecureFileStore
import com.leviknet.vpn.core.telemetry.AttemptCause
import com.leviknet.vpn.core.telemetry.AttemptStage
import com.leviknet.vpn.core.telemetry.EndBy
import com.leviknet.vpn.core.telemetry.NetworkState
import com.leviknet.vpn.core.telemetry.NetworkType
import com.leviknet.vpn.core.telemetry.PowerState
import com.leviknet.vpn.core.telemetry.RecoveryAction
import com.leviknet.vpn.core.telemetry.SessionTrigger
import com.leviknet.vpn.core.telemetry.TelemetrySessionSettings
import com.leviknet.vpn.core.telemetry.safeTelemetryCode
import com.leviknet.vpn.core.telemetry.isBatteryUnrestricted
import com.leviknet.vpn.core.telemetry.telemetryNetworkType
import com.leviknet.vpn.data.DnsProvider
import com.leviknet.vpn.data.RoutingPreset
import com.leviknet.vpn.data.SplitTunnelMode
import com.leviknet.vpn.data.hasUsableYandexAuth
import com.leviknet.vpn.data.isActiveAt
import com.leviknet.vpn.data.isRelayAccessDenied
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LevikVpnService : VpnService() {
    private val coreOwner = NEXT_CORE_OWNER.incrementAndGet()
    // A failed background job (a receipt, a probe) is logged; it must never kill the process and the tunnel with it.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
        AppLogger.w(LOG_TAG, "Background service job failed: ${failure.javaClass.simpleName}")
    })
    private val serviceCommands = VpnServiceCommands(serviceScope)
    private val connectionMutex = Mutex()
    private val mobileEvaluationMutex = Mutex()
    private val mobileSwitchPolicy = MobileServerSwitchPolicy()
    private val tunnelHealthPolicy = TunnelHealthPolicy(
        requiredFailureRounds = AUTO_HEALING_FAILURE_ROUNDS,
        networkGraceMs = AUTO_HEALING_NETWORK_GRACE_MS,
        recoveryCooldownMs = AUTO_HEALING_RECOVERY_COOLDOWN_MS,
        candidateBackoffMs = AUTO_HEALING_CANDIDATE_BACKOFF_MS,
    )
    private val lifecycleGate = ReentrantLock()
    private val destroyed = AtomicBoolean(false)
    private val underlyingNetwork = AtomicReference<Network?>(null)
    private val container by lazy {
        (application as LevikVpnApplication).container
    }
    private val networkMonitor by lazy {
        NetworkMonitor(
            context = this,
            handleAvailable = ::onNetworkAvailable,
            handleLost = ::onNetworkLost,
        )
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var currentEngineRequest: TunnelEngineRequest? = null
    private var currentPreparedSession: PreparedTunnelEngineSession? = null
    private var currentEngineAttempt: Any? = null
    private var currentEngine: TunnelEngineAdapter? = null
    private var currentServer: TunnelServer? = null
    private var currentSubscriptionId: String? = null
    @Volatile
    private var currentServerName: String? = null
    private var currentNetwork: Network? = null
    private var currentBootstrapDnsServer: String? = null
    @Volatile
    private var coreRunning = false
    @Volatile
    private var lockdownActive = false
    private var coreLease: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectJob: Job? = null
    private var statsJob: Job? = null
    private var profileExpiryJob: Job? = null
    private var activeProfileDeadline: MonotonicCredentialDeadline? = null
    private var admittedYandexAuth: YandexProviderAuth? = null
    private var autoHealingJob: Job? = null
    private var serviceRouteConfig: ServiceRouteConfig? = null
    private var serviceRouteRuntime: ServiceRouteRuntime? = null
    private var serviceRouteReporter: (suspend (ServiceRouteReport) -> Unit)? = null
    private var relayEntitlementWatchdogJob: Job? = null
    private var yandexRefreshJob: Job? = null
    private var connectionJob: Job? = null
    private var pauseJob: Job? = null
    private var mobileAutomationJob: Job? = null
    private var mobileNetworkEvaluationJob: Job? = null
    private var mobileEvaluationNetwork: Network? = null
    @Volatile
    private var pendingRegularNetwork: Network? = null
    private var trafficCheckJob: Job? = null
    /** Smart Auto's protocol ladder since the user connected; a new network starts it over. */
    private val smartLadder = SmartLadder()
    private val freezeDetector = TunnelFreezeDetector()
    /** Mobile servers tried under allowlists since they came on; apart from the protocol ladder. */
    private val allowlistLadder = SmartLadder(maxClimbs = ALLOWLIST_MAX_CLIMBS)
    /** The user chose a regular server under allowlists: kept until the network changes. */
    @Volatile
    private var allowlistOverride = false
    @Volatile
    private var lastWhitelistMode = WhitelistMode.UNKNOWN
    /** The last probe's answer, unknown included; it paces the next probe. */
    @Volatile
    private var lastProbeMode = WhitelistMode.UNKNOWN

    /** The mobile server left when allowlists seemed to end, and on which network: the way back. */
    private var allowlistOffSource: Pair<String, Network>? = null

    /** A mobile network where that move carried nothing: its "allowlists ended" is not believed until then. */
    private var allowlistHold: Pair<Network, Long>? = null
    private val allowlistProbeSchedule = AllowlistProbeSchedule()
    /** Wakes the probe loop early: the tunnel struggles, which allowlists coming on look like. */
    private val allowlistProbeWake = Channel<Unit>(Channel.CONFLATED)
    private var automaticRollbackServerId: String? = null
    private var automaticTargetServerId: String? = null
    @Volatile
    private var pendingAutoFallback: PendingAutoFallback? = null
    private val telemetry get() = container.connectionTelemetry
    // Why the next tunnel start happens; set by whoever schedules it.
    @Volatile
    private var nextAttemptCause = AttemptCause.INITIAL
    private val telemetryNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val idle = getSystemService(PowerManager::class.java)?.isDeviceIdleMode ?: return
            telemetry.record { it.power(if (idle) PowerState.DOZE_ON else PowerState.DOZE_OFF) }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LevikVPN:VpnServiceWakeLock",
            )?.apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun fileDescriptorProtector(network: Network, server: TunnelServer): TunnelFileDescriptorProtector {
        val bindingFailureLogged = AtomicBoolean(false)
        // A TUIC server is a regular server: binding is best-effort exactly as for Xray.
        val requireBinding = server.engine !in setOf(TunnelEngineKind.XRAY, TunnelEngineKind.LEVIK_TUIC) ||
            server.networkRequirement != TunnelNetworkRequirement.ANY
        return TunnelFileDescriptorProtector { fd ->
            protectTunnelSocket(
                fd = fd,
                requireNetworkBinding = requireBinding,
                protect = ::protect,
                bind = { socketFd ->
                    // Duplicate the borrowed descriptor; adoptFd violates fdsan ownership.
                    ParcelFileDescriptor.fromFd(socketFd).use { pfd ->
                        network.bindSocket(pfd.fileDescriptor)
                    }
                },
                onBindingFailure = { error ->
                    if (bindingFailureLogged.compareAndSet(false, true)) {
                        AppLogger.w(LOG_TAG, "Socket network binding failed " +
                            "(${error.javaClass.simpleName}); required=$requireBinding")
                    }
                },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(LOG_TAG, "LevikVpnService onCreate")
        container.tunnelEngineRegistry.claimOwner(coreOwner)
        VpnStateStore.claim(coreOwner)
        ServerPinger.registerSocketProtector(coreOwner, ::protectPingSocket, ::protectPingDatagramSocket)
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            idleReceiver,
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceCommands.onStart(startId)
        val action = intent?.action ?: ACTION_CONNECT
        when (action) {
            ACTION_DISCONNECT -> serviceCommands.requestConnection(false)
            ACTION_CONNECT, ACTION_RESUME, ACTION_RECONFIGURE, ACTION_SWITCH_SERVER ->
                serviceCommands.requestConnection(true)
        }
        AppLogger.d(LOG_TAG, "onStartCommand action: $action")
        when (action) {
            ACTION_DISCONNECT -> {
                val endBy = EndBy.entries.firstOrNull { it.wire == intent?.getStringExtra(EXTRA_END_BY) }
                if (endBy == null) {
                    telemetry.finish(EndBy.USER, "user")
                } else {
                    telemetry.finish(endBy, intent?.getStringExtra(EXTRA_END_CODE)?.let(::safeTelemetryCode))
                }
                automaticRollbackServerId = null
                automaticTargetServerId = null
                pendingAutoFallback = null
                pendingRegularNetwork = null
                tunnelHealthPolicy.reset()
                smartLadder.reset()
                allowlistLadder.reset()
                allowlistOverride = false
                pauseJob?.cancel()
                pauseJob = null
                connectionJob?.cancel()
                connectionJob = null
                // OFF wins over a reconnect in flight: it holds the connection lock the stop waits
                // for, and the probes and checks below would otherwise start another attempt.
                reconnectJob?.cancel()
                mobileNetworkEvaluationJob?.cancel()
                mobileAutomationJob?.cancel()
                autoHealingJob?.cancel()
                trafficCheckJob?.cancel()
                VpnStateStore.update(coreOwner) { it.copy(state = VpnConnectionState.STOPPING, failure = null) }
                container.settings.setPausedUntilMs(0L)
                serviceCommands.enqueueStop {
                    stopConnection(stopService = true, stopStartId = startId)
                }
            }
            ACTION_PAUSE -> {
                val minutes = intent?.getIntExtra(EXTRA_PAUSE_MINUTES, 15) ?: 15
                val home = intent?.getStringExtra(EXTRA_PAUSE_HOME)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_HOME_NAME }
                serviceScope.launch {
                    pauseConnection(minutes, home)
                }
            }
            ACTION_RESUME -> {
                serviceScope.launch {
                    resumeConnection()
                }
            }
            ACTION_RECONNECT -> scheduleReconnect(AttemptCause.RECONNECT)
            ACTION_RECONFIGURE, ACTION_SWITCH_SERVER -> {
                if (pauseJob != null) telemetry.record { it.unpause() }
                beginOrContinueTelemetry(SessionTrigger.USER, AttemptCause.SERVER_SWITCH)
                pauseJob?.cancel()
                pauseJob = null
                container.settings.setPausedUntilMs(0L)
                val newServerId = intent?.getStringExtra(EXTRA_SERVER_ID)
                if (newServerId != null) {
                    automaticRollbackServerId = null
                    automaticTargetServerId = null
                    pendingAutoFallback = null
                    tunnelHealthPolicy.reset()
                    smartLadder.reset()
                    allowlistLadder.reset()
                    // A regular server picked by hand under allowlists is the user's to keep.
                    allowlistOverride = !container.settings.automaticServer.value &&
                        lastWhitelistMode == WhitelistMode.ACTIVE &&
                        runCatching { readPreparedProfile() }.getOrNull()?.servers
                            ?.firstOrNull { it.id == newServerId }?.isMobileServer() == false
                    container.secureStore.put(SecureFileStore.SELECTED_SERVER, newServerId.encodeToByteArray())
                }
                connectionJob?.cancel()
                connectionJob = serviceScope.launch {
                    stopConnection(stopService = false)
                    connect()
                }
            }
            else -> {
                val wasPaused = pauseJob != null
                pauseJob?.cancel()
                pauseJob = null
                container.settings.setPausedUntilMs(0L)
                if (coreRunning && !lockdownActive && !serviceCommands.isStopping) {
                    VpnStateStore.update(coreOwner) {
                        it.copy(
                            state = VpnConnectionState.CONNECTED,
                            engine = currentServer?.engine,
                            serverId = currentServer?.id,
                            serverName = currentServerName,
                            serverCountryCode = currentServer?.countryCode,
                            effectiveRoutingProfile = currentServer?.effectiveRoutingProfile(container.settings.routingPreset.value)
                                ?: EffectiveRoutingProfile.USER_SELECTED,
                            failure = null,
                        )
                    }
                    showForeground(VpnConnectionState.CONNECTED, currentServerName)
                    return START_STICKY
                }
                if (connectionJob?.isActive == true) {
                    showForeground(VpnConnectionState.CONNECTING, null)
                    return START_STICKY
                }
                smartLadder.reset()
                if (wasPaused && telemetry.active) {
                    telemetry.record { it.unpause() }
                    nextAttemptCause = AttemptCause.RESUME
                } else {
                    beginOrContinueTelemetry(sessionTrigger(intent), AttemptCause.RECONNECT)
                }
                showForeground(VpnConnectionState.CONNECTING, null)
                connectionJob = serviceScope.launch {
                    connect()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        serviceCommands.requestConnection(false)
        connectionJob?.cancel()
        reconnectJob?.cancel()
        AppLogger.w(LOG_TAG, "VPN permission revoked by system/user")
        telemetry.finish(EndBy.SYSTEM, "permission_revoked")
        VpnStateStore.set(
            coreOwner,
            VpnSnapshot(
                state = VpnConnectionState.ERROR,
                failure = VpnFailure.PERMISSION_REVOKED,
            ),
        )
        serviceScope.launch {
            stopConnection(stopService = true, preserveError = true)
        }
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (coreRunning) {
            showForeground(VpnConnectionState.CONNECTED, currentServerName)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceCommands.requestConnection(false)
        serviceRouteRuntime?.close()
        serviceRouteRuntime = null
        serviceRouteReporter = null
        serviceRouteConfig?.close()
        serviceRouteConfig = null
        container.repository.serviceRoutesStopped()
        AppLogger.i(LOG_TAG, "LevikVpnService onDestroy")
        telemetry.finish(EndBy.SYSTEM, null)
        runCatching { unregisterReceiver(idleReceiver) }
        ServerPinger.unregisterSocketProtector(coreOwner)
        runCatching { container.trafficHistoryStore.flushAsync() }
        val cleanup = lifecycleGate.withLock {
            destroyed.set(true)
            lockdownActive = false
            pendingAutoFallback = null
            tunnelHealthPolicy.reset()
            pauseJob?.cancel()
            pauseJob = null
            connectionJob?.cancel()
            reconnectJob?.cancel()
            statsJob?.cancel()
            profileExpiryJob?.cancel()
            autoHealingJob?.cancel()
            yandexRefreshJob?.cancel()
            yandexRefreshJob = null
            relayEntitlementWatchdogJob?.cancel()
            mobileAutomationJob?.cancel()
            mobileNetworkEvaluationJob?.cancel()
            networkMonitor.stop(releaseCellular = false)
            underlyingNetwork.set(null)
            val capturedLease = coreLease
            val capturedEngine = currentEngine
            val capturedPrepared = currentPreparedSession
            val capturedTun = tunInterface
            coreRunning = false
            coreLease = null
            tunInterface = null
            currentEngineRequest = null
            currentPreparedSession = null
            currentEngineAttempt = null
            currentEngine = null
            currentServer = null
            currentSubscriptionId = null
            currentServerName = null
            currentNetwork = null
            currentBootstrapDnsServer = null
            NativeCleanup(capturedEngine, capturedPrepared, capturedLease, capturedTun)
        }
        serviceScope.cancel()
        container.nativeCleanupScope.launch {
            try {
                runCatching {
                    cleanup.engine?.stop(coreOwner, cleanup.prepared, cleanup.lease)
                }
            } finally {
                runCatching { cleanup.tunInterface?.close() }
                networkMonitor.releaseCellularNetwork()
                container.tunnelEngineRegistry.retireOwner(coreOwner)
            }
        }
        VpnStateStore.release(coreOwner, preserveTerminalError = true)
        super.onDestroy()
    }

    private suspend fun connect() {
        // A quick OFF/ON must wait for the old core and TUN to finish closing. The stop job
        // belongs to the service, so cancelling this connection cannot cancel that cleanup.
        serviceCommands.awaitPendingStop()
        coroutineContext.ensureActive()
        if (serviceCommands.connectionGeneration == null) return
        connectAfterStop()
    }

    private suspend fun connectAfterStop(): Unit = connectionMutex.withLock connection@{
        coroutineContext.ensureActive()
        val generation = serviceCommands.connectionGeneration ?: return@connection
        if (destroyed.get()) return@connection
        val fallbackAttempt = pendingAutoFallback
        var attemptedServerId: String? = null
        if (lockdownActive) {
            // A fresh connect attempt always replaces the Kill Switch lockdown TUN.
            lockdownActive = false
            stopCoreAndTun()
        }
        if (coreRunning) return@connection
        val attemptCause = if (fallbackAttempt != null) AttemptCause.FAILOVER else nextAttemptCause
        nextAttemptCause = AttemptCause.RECONNECT
        val progress = AttemptProgress(AttemptStage.PROFILE, "profile_missing")
        VpnStateStore.set(
            coreOwner,
            VpnSnapshot(state = VpnConnectionState.CONNECTING),
        )
        showForeground(VpnConnectionState.CONNECTING, null)

        try {
            checkDisclosureConsent()
            var profile = readPreparedProfile()
            val selectedId = fallbackAttempt?.targetServerId ?: readSelectedServerId()
            val cachedSelected = profile.servers.firstOrNull { it.id == selectedId }
            if (cachedSelected?.engine == TunnelEngineKind.LEVIK_RELAY) {
                progress.reach(AttemptStage.PROFILE, "relay_access_refresh")
                profile = container.repository.prepareRelayConnection(profile.subscriptionId)
                require(profile.servers.any { it.id == selectedId && it.engine == TunnelEngineKind.LEVIK_RELAY }) {
                    "Selected relay server is unavailable"
                }
            }
            progress.reach(AttemptStage.PROFILE, "subscription_expired")
            profile.subscriptionExpiresAt?.let { value ->
                require(Instant.parse(value).isAfter(Instant.now())) {
                    "Subscription has expired"
                }
            }
            progress.reach(AttemptStage.PROFILE, "config_invalid")
            attemptedServerId = selectedId
            val selected = if (fallbackAttempt != null) {
                profile.servers.firstOrNull { it.id == selectedId }
                    ?: error("Auto-failover server is no longer present in the tunnel profile")
            } else {
                profile.servers.firstOrNull { it.id == selectedId }
                    ?: profile.servers.firstOrNull(TunnelServer::isEligibleForAutomaticSelection)
                    // Levik Game alone has no ordinary servers: Game is the subscription's only server.
                    ?: profile.servers.singleOrNull { it.engine == TunnelEngineKind.LEVIK_GAME }
                    ?: error("Tunnel profile has no server eligible for automatic selection")
            }
            // This separate request is gated and endpoint-specific; LTE and relay profiles are untouched.
            profile = container.repository.prepareServiceRoutes(profile, selected, container.appPlatform.flagEnabled("service_routes"))
            val routes = ServiceRouteConfig.prepare(profile, selected, filesDir, container.settings.autoFallbackServer.value)
            serviceRouteConfig = routes
            telemetry.record { it.attempt(selected.name, selected.telemetryProtocol(), attemptCause) }
            progress.reach(AttemptStage.PROFILE, "profile_expired")
            val yandexExpiry = selected.yandexConfig?.let {
                Instant.ofEpochSecond(it.expiresAt).toString()
            }
            val connectionExpiresAt = (yandexExpiry ?: selected.relayConfig?.bootstrap?.expiresAt)
                ?.also { value ->
                    require(Instant.parse(value).isAfter(Instant.now())) {
                        "Relay credential has expired"
                    }
                }
                ?.let { relayExpiry ->
                    listOfNotNull(profile.subscriptionExpiresAt, relayExpiry)
                        .minBy { Instant.parse(it) }
                }
                ?: profile.subscriptionExpiresAt
            val connectionExpiryDeadline = connectionExpiresAt?.let { value ->
                MonotonicCredentialDeadline.create(
                    expiresAt = Instant.parse(value),
                    wallClockNow = Instant.now(),
                    elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                )
            }

            AppLogger.i(LOG_TAG, "Establishing VPN connection")

            progress.reach(AttemptStage.TUN, "unreachable")
            val network = networkMonitor.acquireNetwork(selected.networkRequirement)
                ?: if (selected.networkRequirement == TunnelNetworkRequirement.CELLULAR_ALLOWLIST) {
                    throw TunnelNetworkRequirementException(
                        TunnelNetworkRequirementViolation.CELLULAR_NETWORK_REQUIRED,
                    )
                } else {
                    throw NetworkSetupException("No usable underlying network")
                }
            currentNetwork = network
            enforceNetworkRequirement(selected, network)
            underlyingNetwork.set(network)
            prepareTelemetryNetwork(network)
            val bootstrapDnsServer = resolveBootstrapDnsServer(selected, network)
            currentBootstrapDnsServer = bootstrapDnsServer
            progress.reach(AttemptStage.CORE, "config_invalid")
            val dnsProvider = container.settings.dnsProvider.value
            val primaryDns = if (dnsProvider == DnsProvider.CUSTOM) {
                container.settings.customDnsIpv4.value.trim().ifBlank { dnsProvider.primaryIpv4 }
            } else {
                dnsProvider.primaryIpv4
            }
            val secondaryDns = if (dnsProvider == DnsProvider.CUSTOM) "8.8.8.8" else dnsProvider.secondaryIpv4

            val routingPreset = container.settings.routingPreset.value
            val routingProfile = selected.effectiveRoutingProfile(routingPreset)
            val antiDpi = container.settings.antiDpiEnabled.value
            val useDoh = container.settings.useDoh.value
            val dohUrl = if (useDoh) {
                if (dnsProvider == DnsProvider.CUSTOM) {
                    container.settings.customDohUrl.value.ifBlank { null }
                } else {
                    dnsProvider.dohUrl
                }
            } else null

            // Regular servers and the TUIC sidecar share one Xray configuration path.
            fun regularXrayConfig(
                buildProfile: PreparedTunnelProfile,
                tunFileDescriptor: Int,
                antiDpiEnabled: Boolean,
            ): String = XrayConfigBuilder(container.json).build(
                profile = buildProfile,
                selectedServerId = selected.id,
                tunFileDescriptor = tunFileDescriptor,
                routingPreset = routingPreset,
                bypassRussianTraffic = container.settings.bypassRussianTraffic.value,
                russianDirectCidrs = container.russianRoutingData.cidrs,
                primaryDnsIp = primaryDns,
                secondaryDnsIp = secondaryDns,
                dohEndpoint = dohUrl,
                antiDpiEnabled = antiDpiEnabled,
                antiDpiPackets = container.settings.antiDpiPackets.value,
                antiDpiLength = container.settings.antiDpiLength.value,
                antiDpiInterval = container.settings.antiDpiInterval.value,
                customDirectDomains = container.settings.customDirectDomains.value,
                customProxyDomains = container.settings.customProxyDomains.value,
                effectiveRoutingProfile = routingProfile,
                lteDirectCidrs = if (routingProfile == EffectiveRoutingProfile.LTE) {
                    container.lteRoutingData.cidrs
                } else {
                    emptyList()
                },
                lteDirectDomains = if (routingProfile == EffectiveRoutingProfile.LTE) {
                    container.lteRoutingData.domains
                } else {
                    emptyList()
                },
                xhttpMuxHosts = container.appPlatform.xhttpMuxHosts(),
                serviceRouteSession = routes,
            )

            val request = when (selected.engine) {
                TunnelEngineKind.LEVIK_DNSTT -> TunnelEngineRequest.Dnstt(
                    config = requireNotNull(selected.dnsttConfig),
                    configFactory = RelayXrayConfigFactory { tunFileDescriptor, proxy ->
                        dnsttTcpConfig(XrayConfigBuilder(container.json).buildRelayProxy(
                            profile = profile, tunFileDescriptor = tunFileDescriptor, proxy = proxy,
                            primaryDnsIp = "77.88.8.8", secondaryDnsIp = "77.88.8.1",
                            routingPreset = routingPreset,
                            customDirectDomains = container.settings.customDirectDomains.value,
                            customProxyDomains = container.settings.customProxyDomains.value,
                            lteDirectCidrs = if (routingPreset == RoutingPreset.BYPASS_RU) container.lteRoutingData.cidrs else emptyList(),
                            lteDirectDomains = if (routingPreset == RoutingPreset.BYPASS_RU) container.lteRoutingData.domains else emptyList(),
                        ))
                    },
                    tunPlan = xrayTunPlan(primaryDns = "77.88.8.8", secondaryDns = "77.88.8.1")
                        .copy(extraIpv4Routes = listOf(DNSTT_FAKE_IP_POOL)),
                )
                TunnelEngineKind.XRAY -> TunnelEngineRequest.Xray(
                    configFactory = XrayConfigFactory { tunFileDescriptor ->
                        regularXrayConfig(profile, tunFileDescriptor, antiDpi)
                    },
                    tunPlan = xrayTunPlan(
                        primaryDns = primaryDns,
                        secondaryDns = secondaryDns,
                    ),
                )
                TunnelEngineKind.LEVIK_TUIC -> TunnelEngineRequest.Tuic(
                    config = requireNotNull(selected.tuicConfig) {
                        "TUIC server has no configuration"
                    },
                    configFactory = RelayXrayConfigFactory { tunFileDescriptor, proxy ->
                        // TLS fragmentation applies to TCP; the QUIC sidecar hop never uses it.
                        regularXrayConfig(
                            XrayConfigBuilder(container.json).withTuicProxy(profile, selected.id, proxy),
                            tunFileDescriptor,
                            antiDpiEnabled = false,
                        )
                    },
                    tunPlan = xrayTunPlan(
                        primaryDns = primaryDns,
                        secondaryDns = secondaryDns,
                    ),
                )
                TunnelEngineKind.LEVIK_GAME -> {
                    val gameConfig = requireNotNull(selected.gameConfig) {
                        "Levik Game server has no configuration"
                    }
                    // The game's sign-in, shop and chat (TCP, DNS, QUIC) take the fastest ordinary
                    // server, so games that Russia blocks work; Levik Game alone keeps them direct.
                    val carrier = if (gameConfig.only) null else gameCarrier(profile, network)
                    GameStatusStore.setCarrier(carrier?.name)
                    AppLogger.i(LOG_TAG, "Levik Game: other game traffic ${if (carrier == null) "goes direct" else "takes ${carrier.smartProtocol()}"}")
                    TunnelEngineRequest.Game(
                    config = gameConfig,
                    preferredExitId = container.settings.gameExitId.value,
                    configFactory = GameXrayConfigFactory { tunFileDescriptor, endpoint ->
                        XrayConfigBuilder(container.json).buildGame(
                            config = gameConfig,
                            tunFileDescriptor = tunFileDescriptor,
                            endpoint = endpoint,
                            primaryDnsIp = primaryDns,
                            secondaryDnsIp = secondaryDns,
                            carrier = carrier,
                            russianDirectCidrs = container.russianRoutingData.cidrs,
                            xhttpMuxHosts = container.appPlatform.xhttpMuxHosts(),
                        )
                    },
                    tunPlan = xrayTunPlan(
                        primaryDns = primaryDns,
                        secondaryDns = secondaryDns,
                    ),
                )
                }
                TunnelEngineKind.LEVIK_RELAY -> TunnelEngineRequest.Relay(
                    config = requireNotNull(selected.relayConfig) {
                        "Relay server has no bootstrap configuration"
                    },
                    configFactory = RelayXrayConfigFactory { tunFileDescriptor, proxy ->
                        XrayConfigBuilder(container.json).buildRelayProxy(
                            profile = profile,
                            tunFileDescriptor = tunFileDescriptor,
                            proxy = proxy,
                            primaryDnsIp = primaryDns,
                            secondaryDnsIp = secondaryDns,
                            routingPreset = routingPreset,
                            customDirectDomains = container.settings.customDirectDomains.value,
                            customProxyDomains = container.settings.customProxyDomains.value,
                            lteDirectCidrs = if (routingPreset == RoutingPreset.BYPASS_RU) {
                                container.lteRoutingData.cidrs
                            } else emptyList(),
                            lteDirectDomains = if (routingPreset == RoutingPreset.BYPASS_RU) {
                                container.lteRoutingData.domains
                            } else emptyList(),
                        )
                    },
                    tunPlan = xrayTunPlan(
                        primaryDns = primaryDns,
                        secondaryDns = secondaryDns,
                    ),
                )
                TunnelEngineKind.LEVIK_YANDEX -> TunnelEngineRequest.Yandex(
                    config = requireNotNull(selected.yandexConfig) {
                        "Yandex server has no bootstrap configuration"
                    },
                    configFactory = RelayXrayConfigFactory { tunFileDescriptor, proxy ->
                        XrayConfigBuilder(container.json).buildRelayProxy(
                            profile = profile,
                            resolveTargets = true,
                            tunFileDescriptor = tunFileDescriptor,
                            proxy = proxy,
                            primaryDnsIp = primaryDns,
                            secondaryDnsIp = secondaryDns,
                            routingPreset = routingPreset,
                            customDirectDomains = container.settings.customDirectDomains.value,
                            customProxyDomains = container.settings.customProxyDomains.value,
                            lteDirectCidrs = if (routingPreset == RoutingPreset.BYPASS_RU) {
                                container.lteRoutingData.cidrs
                            } else emptyList(),
                            lteDirectDomains = if (routingPreset == RoutingPreset.BYPASS_RU) {
                                container.lteRoutingData.domains
                            } else emptyList(),
                        )
                    },
                    tunPlan = xrayTunPlan(
                        primaryDns = primaryDns,
                        secondaryDns = secondaryDns,
                        ipv6Enabled = false,
                    ),
                )
            }
            val engine = container.tunnelEngineRegistry.require(selected.engine)
            val engineAttempt = Any()
            check(!destroyed.get()) { "VPN service was destroyed during startup" }
            progress.reach(startStage(selected), "core_start_failed")
            val prepared = engine.prepare(
                owner = coreOwner,
                request = request,
                environment = TunnelEngineEnvironment(
                    network = network,
                    protector = fileDescriptorProtector(network, selected),
                    dnsServer = bootstrapDnsServer,
                    unboundSocketProtector = { fd -> protectUnboundTunnelSocket(fd, ::protect) },
                    terminalFailureHandler = { code -> onTunnelEngineTerminalFailure(code, engineAttempt) },
                ),
            )
            val acceptedPrepared = lifecycleGate.withLock {
                if (destroyed.get() || !coroutineContext.isActive || !serviceCommands.permitsConnection(generation)) {
                    false
                } else {
                    currentEngineRequest = request
                    currentPreparedSession = prepared
                    currentEngineAttempt = engineAttempt
                    currentEngine = engine
                    currentServer = selected
                    currentSubscriptionId = profile.subscriptionId
                    currentServerName = selected.name
                    true
                }
            }
            if (!acceptedPrepared) {
                runCatching { engine.stop(coreOwner, prepared) }
                coroutineContext.ensureActive()
                return@connection
            }
            checkConnectionDeadline(connectionExpiryDeadline)
            progress.reach(AttemptStage.TUN, "tun_failed")
            val tun = try {
                establishTun(selected.name, prepared.tunPlan, gamePackagesFor(selected))
            } catch (error: SecurityException) {
                throw error
            } catch (error: RuntimeException) {
                throw NetworkSetupException("Unable to establish the Android VPN", error)
            }
            val acceptedTun = lifecycleGate.withLock {
                if (destroyed.get()) {
                    false
                } else {
                    tunInterface = tun
                    true
                }
            }
            if (!acceptedTun) {
                runCatching { tun.close() }
                return@connection
            }
            if (!setUnderlyingNetworks(arrayOf(network))) {
                throw NetworkSetupException(
                    "Unable to bind the VPN to its underlying network",
                )
            }
            progress.reach(startStage(selected), "core_start_failed")
            coreLease = engine.start(
                owner = coreOwner,
                prepared = prepared,
                tun = AndroidTunnelFileDescriptorHandle(tun),
            )
            coroutineContext.ensureActive()
            checkConnectionDeadline(connectionExpiryDeadline)
            coreRunning = true
            if (routes != null) {
                val (splitMode, splitPackages) = withAccountDirectApps(
                    container.settings.splitTunnelMode.value, container.settings.splitTunnelPackages.value,
                    if (BuildConfig.IS_PLAY_DISTRIBUTION) emptyList() else profile.directApps,
                )
                val exclusions = splitMode == SplitTunnelMode.ALLOWED && splitPackages.isNotEmpty() ||
                    splitMode == SplitTunnelMode.DISALLOWED && splitPackages.any { pkg ->
                        pkg != packageName && try { packageManager.getApplicationInfo(pkg, 0); true }
                        catch (_: PackageManager.NameNotFoundException) { false }
                    }
                serviceRouteReporter = { report ->
                    container.repository.reportServiceRoutes(if (exclusions && report.state == "applied" && report.detail == null)
                        report.copy(detail = "app_exclusions_active") else report, profile.serviceRouteOwner)
                }
                startServiceRouteRuntime()
            } else if (profile.serviceRoutes?.rules?.isEmpty() == true) {
                val policy = requireNotNull(profile.serviceRoutes)
                serviceScope.launch {
                    try {
                        container.repository.reportServiceRoutes(ServiceRouteReport(policy.revision, policy.fingerprint, "applied", emptyList()), profile.serviceRouteOwner)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { /* Optional receipt: an offline control plane or an old target never ends the tunnel. */ }
                }
            } else if (container.repository.serviceRouteState.value == "suspended") {
                serviceScope.launch { container.repository.reportSuspendedServiceRoutes(profile.subscriptionId) }
            }
            tunnelHealthPolicy.onTunnelStarted(SystemClock.elapsedRealtime())
            if (automaticTargetServerId == selected.id) {
                automaticRollbackServerId = null
                automaticTargetServerId = null
            }
            // Back on a regular server: the way back from allowlists is done.
            if (!selected.isMobileServer()) container.secureStore.remove(SecureFileStore.ALLOWLIST_RETURN_SERVER)
            val published = lifecycleGate.withLock {
                if (destroyed.get() || !serviceCommands.permitsConnection(generation)) return@withLock false
                pendingRegularNetwork = null
                networkMonitor.start()
                startStats()
                startAutoHealing()
                startTrafficCheck(selected, network)
                startMobileServerAutomation()
                startRelayEntitlementWatchdog(selected, prepared)
                connectionExpiryDeadline?.let(::scheduleProfileExpiry)
                admittedYandexAuth = selected.yandexConfig?.bootstrap?.providerAuth
                startYandexRefresh(prepared)
                VpnStateStore.set(
                    coreOwner,
                    VpnSnapshot(
                        state = VpnConnectionState.CONNECTED,
                        engine = selected.engine,
                        subscriptionId = profile.subscriptionId,
                        serverId = selected.id,
                        serverName = selected.name,
                        serverCountryCode = selected.countryCode,
                        effectiveRoutingProfile = routingProfile,
                    ),
                )
                showForeground(VpnConnectionState.CONNECTED, selected.name)
                true
            }
            if (!published) {
                stopCoreAndTun()
                return@connection
            }
            acquireWakeLock()
            // Android has no end-to-end check at startup: "connected" means the core started.
            telemetry.record { it.connected() }
            telemetry.flushSoon()
            AppLogger.i(LOG_TAG, "VPN interface and core started; end-to-end connectivity is monitored separately")
        } catch (error: CancellationException) {
            stopCoreAndTun()
            throw error
        } catch (error: Throwable) {
            stopCoreAndTun()
            coroutineContext.ensureActive()
            if (!serviceCommands.permitsConnection(generation)) return@connection
            AppLogger.e(LOG_TAG, "VPN startup failed", error)
            val (failedStage, failedCode) = progress.failure(error)
            telemetry.record { it.attemptFailed(failedStage, failedCode) }
            val failedFallback = fallbackAttempt?.takeIf { attempt ->
                attempt.targetServerId == attemptedServerId &&
                    pendingAutoFallback?.targetServerId == attempt.targetServerId
            }
            if (failedFallback != null) {
                pendingAutoFallback = null
                tunnelHealthPolicy.markCandidateFailed(
                    failedFallback.targetServerId,
                    SystemClock.elapsedRealtime(),
                )
                runCatching {
                    container.secureStore.put(
                        SecureFileStore.SELECTED_SERVER,
                        failedFallback.sourceServerId.encodeToByteArray(),
                    )
                }.onFailure { restoreError ->
                    AppLogger.e(LOG_TAG, "Failed to restore server after auto-failover startup failure", restoreError)
                }
                AppLogger.w(
                    LOG_TAG,
                    "Auto-failover candidate failed during startup; returning to previous server",
                )
                VpnStateStore.update(coreOwner) {
                    it.copy(state = VpnConnectionState.RECONNECTING, failure = null)
                }
                showForeground(VpnConnectionState.RECONNECTING, null)
                telemetry.record { it.recovery(RecoveryAction.ROLLBACK) }
                nextAttemptCause = AttemptCause.ROLLBACK
                connectionJob = serviceScope.launch {
                    delay(AUTOMATIC_SWITCH_ROLLBACK_DELAY_MS)
                    connect()
                }
                return@connection
            }
            val failedAllowlistServer = attemptedServerId
                ?.takeIf { fallbackAttempt == null && lastWhitelistMode == WhitelistMode.ACTIVE && allowlistSessionActive() }
                ?.let { id -> runCatching { readPreparedProfile() }.getOrNull()?.servers?.firstOrNull { it.id == id } }
                ?.takeIf(TunnelServer::isMobileServer)
            if (failedAllowlistServer != null && !allowlistLadder.exhausted()) {
                // Under allowlists the server left behind carries nothing: the next mobile server first.
                VpnStateStore.update(coreOwner) {
                    it.copy(state = VpnConnectionState.RECONNECTING, failure = null)
                }
                showForeground(VpnConnectionState.RECONNECTING, null)
                val network = networkMonitor.activeNetwork()
                connectionJob = serviceScope.launch {
                    if (advanceAllowlist(failedAllowlistServer, network, requireRunning = false) == null) {
                        rollBackAllowlistSwitch()
                    }
                    delay(AUTOMATIC_SWITCH_ROLLBACK_DELAY_MS)
                    connect()
                }
                return@connection
            }
            val rollbackServerId = automaticRollbackServerId
                ?.takeIf { automaticTargetServerId == readSelectedServerId() }
            if (rollbackServerId != null) {
                automaticRollbackServerId = null
                automaticTargetServerId = null
                container.secureStore.put(
                    SecureFileStore.SELECTED_SERVER,
                    rollbackServerId.encodeToByteArray(),
                )
                VpnStateStore.update(coreOwner) {
                    it.copy(state = VpnConnectionState.RECONNECTING, failure = null)
                }
                showForeground(VpnConnectionState.RECONNECTING, null)
                telemetry.record { it.recovery(RecoveryAction.ROLLBACK) }
                nextAttemptCause = AttemptCause.ROLLBACK
                connectionJob = serviceScope.launch {
                    delay(AUTOMATIC_SWITCH_ROLLBACK_DELAY_MS)
                    connect()
                }
                return@connection
            }
            val failure = when (error) {
                is UnsatisfiedLinkError -> VpnFailure.CORE_UNAVAILABLE
                is TunnelEngineUnavailableException -> VpnFailure.CORE_UNAVAILABLE
                is TunnelEngineFailureException -> when (error.code) {
                    "relay_native_missing", "relay_process_start_failed", "game_native_missing" ->
                        VpnFailure.CORE_UNAVAILABLE
                    "relay_credential_expired" -> VpnFailure.INVALID_PROFILE
                    else -> VpnFailure.NETWORK
                }
                is TunnelNetworkRequirementException -> VpnFailure.NETWORK_REQUIREMENT
                is NetworkSetupException -> VpnFailure.NETWORK
                is ApiException.Network -> VpnFailure.NETWORK
                is java.io.IOException -> VpnFailure.NETWORK
                is ApiException.Rejected -> if (error.retryable) VpnFailure.NETWORK else VpnFailure.INVALID_PROFILE
                is SecurityException -> VpnFailure.PERMISSION_REVOKED
                else -> VpnFailure.INVALID_PROFILE
            }
            val detail = when {
                error is ApiException -> relayAccessFailureDetail(error)
                error is TunnelEngineFailureException -> relayFailureDetail(error.code)
                error is XrayException -> error.message
                error.cause is XrayException -> error.cause?.message
                else -> error.message?.takeIf { it.isNotBlank() }
            }?.take(MAX_FAILURE_DETAIL_LENGTH)
            VpnStateStore.set(
                coreOwner,
                VpnSnapshot(
                    state = VpnConnectionState.ERROR,
                    failure = failure,
                    failureDetail = detail,
                ),
            )
            val enteredLockdown = enterKillSwitchLockdownLocked(detail)
            endTelemetryAfterFailure(enteredLockdown, failedCode)
            if (!enteredLockdown) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun stopConnection(
        stopService: Boolean,
        preserveError: Boolean = false,
        stopStartId: Int? = null,
    ) = connectionMutex.withLock {
        lockdownActive = false
        if (!preserveError) {
            VpnStateStore.update(coreOwner) { state ->
                state.copy(state = VpnConnectionState.STOPPING, failure = null)
            }
        }
        reconnectJob?.cancel()
        reconnectJob = null
        statsJob?.cancel()
        statsJob = null
        profileExpiryJob?.cancel()
        profileExpiryJob = null
        activeProfileDeadline = null
        admittedYandexAuth = null
        autoHealingJob?.cancel()
        autoHealingJob = null
        yandexRefreshJob?.cancel()
        yandexRefreshJob = null
        relayEntitlementWatchdogJob?.cancel()
        relayEntitlementWatchdogJob = null
        mobileAutomationJob?.cancel()
        mobileAutomationJob = null
        mobileNetworkEvaluationJob?.cancel()
        mobileNetworkEvaluationJob = null
        trafficCheckJob?.cancel()
        trafficCheckJob = null
        networkMonitor.stop(releaseCellular = false)
        stopCoreAndTun()
        runCatching { container.trafficHistoryStore.flush() }
        // onStartCommand and this final decision must run on the same thread: an old OFF
        // must neither remove a newer foreground notification nor stop a newer ON request.
        withContext(Dispatchers.Main.immediate) {
            if (stopStartId != null && !serviceCommands.isLatest(stopStartId)) return@withContext
            if (!preserveError) {
                VpnStateStore.set(coreOwner, VpnSnapshot())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopService) {
                if (stopStartId != null) stopSelfResult(stopStartId) else stopSelf()
            }
        }
    }

    private fun stopCoreAndTun() {
        serviceRouteRuntime?.close()
        serviceRouteRuntime = null
        serviceRouteReporter = null
        serviceRouteConfig?.close()
        serviceRouteConfig = null
        container.repository.serviceRoutesStopped()
        releaseWakeLock()
        if (currentEngine != null) {
            runCatching {
                currentEngine?.stop(coreOwner, currentPreparedSession, coreLease)
            }
        }
        networkMonitor.releaseCellularNetwork()
        coreRunning = false
        coreLease = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        currentEngineRequest = null
        currentPreparedSession = null
        currentEngineAttempt = null
        currentEngine = null
        currentServer = null
        currentSubscriptionId = null
        currentServerName = null
        currentNetwork = null
        currentBootstrapDnsServer = null
        underlyingNetwork.set(null)
    }

    private fun startServiceRouteRuntime() {
        val config = serviceRouteConfig ?: return
        val reporter = serviceRouteReporter ?: return
        val lease = coreLease
        serviceRouteRuntime = ServiceRouteRuntime(config, serviceScope) { report ->
            if (coreRunning && coreLease == lease && serviceRouteConfig === config) reporter(report)
        }.also { it.start() }
    }

    /** Levik Game carries only the selected games; null for every other server. */
    private fun gamePackagesFor(server: TunnelServer): Set<String>? {
        if (server.engine != TunnelEngineKind.LEVIK_GAME) return null
        // Saved or imported choices are checked again: only installed games without a VPN of their own.
        val packages = GameApps.eligible(packageManager, container.settings.gamePackages.value, packageName)
        if (packages.isEmpty()) throw TunnelEngineFailureException("game_no_apps")
        return packages
    }

    private fun establishTun(
        serverName: String,
        tunPlan: TunPlan,
        gamePackages: Set<String>? = null,
    ): ParcelFileDescriptor {
        val useNativeExclusions = VpnRoutes.supportsNativeExclusions()
        return try {
            establishTun(serverName, tunPlan, useNativeExclusions, gamePackages)
        } catch (error: RuntimeException) {
            if (!VpnRoutes.shouldRetryWithCompatibleRoutes(useNativeExclusions, error)) {
                throw error
            }
            AppLogger.w(
                LOG_TAG,
                "Android rejected native VPN route exclusions; retrying with compatible routes",
                error,
            )
            establishTun(serverName, tunPlan, useNativeExclusions = false, gamePackages = gamePackages)
        }
    }

    private fun establishTun(
        serverName: String,
        tunPlan: TunPlan,
        useNativeExclusions: Boolean,
        gamePackages: Set<String>?,
    ): ParcelFileDescriptor {
        val configureIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The account's rules (bank apps directly, say) reach the Direct build only.
        val accountDirectApps = if (BuildConfig.IS_PLAY_DISTRIBUTION) {
            emptyList()
        } else {
            runCatching { readPreparedProfile().directApps }.getOrDefault(emptyList())
        }
        val (splitMode, splitPackages) = withAccountDirectApps(
            container.settings.splitTunnelMode.value,
            container.settings.splitTunnelPackages.value,
            accountDirectApps,
        )

        return Builder()
            .setSession(getString(R.string.vpn_session_name, serverName))
            .setConfigureIntent(configureIntent)
            .setMtu(tunPlan.mtu)
            .apply {
                tunPlan.addresses.forEach { address ->
                    addAddress(address.address, address.prefixLength)
                }
                tunPlan.dnsServers.forEach(::addDnsServer)
            }
            .setBlocking(true)
            .apply {
                // With no IPv6 address, DNS or route, Android blocks the family
                // before accepting a connection. Do not call allowFamily:
                // that would permit fallback to the physical network.
                VpnRoutes.apply(this, useNativeExclusions, includeIpv6 = tunPlan.ipv6Enabled)
                // More specific than the 198.18.0.0/15 exclusion, so it wins on lookup.
                tunPlan.extraIpv4Routes.forEach { cidr ->
                    val (address, prefix) = VpnRoutes.splitCidr(cidr)
                    addRoute(address, prefix)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(false)
                }
                // Android excludes the app UID before IP/domain routing, on every server.
                // Levik Game: only the selected games enter the VPN, whatever the split settings.
                applySplitTunnelApplications(
                    mode = if (gamePackages != null) SplitTunnelMode.ALLOWED else splitMode,
                    configuredPackages = gamePackages ?: splitPackages,
                    vpnPackageName = packageName,
                    addAllowed = { pkg ->
                        try {
                            addAllowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            // Saved selections may include apps that were uninstalled.
                        }
                    },
                    addDisallowed = { pkg ->
                        try {
                            addDisallowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            // Do not swallow other failures and silently tunnel excluded apps.
                        }
                    },
                )
            }
            .establish()
            ?: throw NetworkSetupException("Android denied the VPN interface")
    }

    private fun protectPingSocket(socket: Socket): Boolean {
        if (!coreRunning) return true
        val network = underlyingNetwork.get() ?: return false
        return try {
            network.bindSocket(socket)
            protect(socket)
        } catch (_: Exception) {
            false
        }
    }

    private fun protectPingDatagramSocket(socket: DatagramSocket): Boolean {
        if (!coreRunning) return true
        val network = underlyingNetwork.get() ?: return false
        return try {
            network.bindSocket(socket)
            protect(socket)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveBootstrapDnsServer(server: TunnelServer, network: Network): String? {
        if (server.engine != TunnelEngineKind.XRAY) return null
        val dnsServer = networkMonitor.awaitBootstrapDnsServer(network)
        if (dnsServer == null && requiresBootstrapDns(ServerPinger.extractEndpoint(server.outbound)?.first)) {
            // An unprotected/system fallback can resolve through our own TUN after startup.
            // Wait for the physical network to supply a resolver instead of looping or using
            // a public DNS endpoint that may be blocked by the mobile allow-list.
            throw NetworkSetupException("Underlying network DNS is unavailable")
        }
        return dnsServer
    }

    private suspend fun enforceNetworkRequirement(
        server: TunnelServer,
        network: Network,
    ) {
        if (server.networkRequirement == TunnelNetworkRequirement.ANY) return
        val violation = tunnelNetworkRequirementViolation(
            requirement = server.networkRequirement,
            isCellularNetwork = networkMonitor.isCellular(network),
        )
        if (violation != null) throw TunnelNetworkRequirementException(violation)
    }

    /** Allowlists move both an automatic and a hand-picked server, docs/app-platform.md "Allowlists". */
    private fun startMobileServerAutomation() {
        mobileAutomationJob?.cancel()
        allowlistProbeSchedule.reset()
        mobileAutomationJob = serviceScope.launch {
            delay(MOBILE_AUTOMATION_INITIAL_DELAY_MS)
            while (coreRunning) {
                val network = networkMonitor.activeNetwork()
                val waitMs = if (allowlistProbeNeeded(network)) {
                    evaluateMobileServerPolicy(network)
                    // A switch waiting for its confirmation should not wait minutes.
                    if (mobileSwitchPolicy.hasPendingCandidate()) {
                        MOBILE_CONFIRM_INTERVAL_MS
                    } else {
                        allowlistProbeSchedule.next(lastProbeMode, currentServer?.isMobileServer() == true)
                    }
                } else {
                    // Wi-Fi on a regular server: allowlists change nothing there; a new network wakes the loop.
                    MOBILE_IDLE_INTERVAL_MS
                }
                withTimeoutOrNull(waitMs) { allowlistProbeWake.receive() }
            }
        }
    }

    /** Allowlists move a connection only on mobile data, or back from a mobile server. */
    private fun allowlistProbeNeeded(network: Network?): Boolean =
        network != null && (networkMonitor.isCellular(network) || currentServer?.isMobileServer() == true)

    /** The tunnel struggles: probe for allowlists now and at the short interval again. */
    private fun wakeAllowlistProbe() {
        allowlistProbeSchedule.reset()
        allowlistProbeWake.trySend(Unit)
    }

    @Synchronized
    private fun scheduleMobilePolicyEvaluation() {
        if (!coreRunning || serviceCommands.isStopping || serviceCommands.connectionGeneration == null) return
        // A retained cellular request emits callbacks too. Always evaluate the preferred network,
        // and do not let repeated LinkProperties/capability updates restart its pending probe.
        val network = networkMonitor.activeNetwork() ?: return
        if (mobileEvaluationNetwork == network && mobileNetworkEvaluationJob?.isActive == true) return
        mobileEvaluationNetwork = network
        // A new network: probe it at the short interval again.
        allowlistProbeSchedule.reset()
        mobileNetworkEvaluationJob?.cancel()
        mobileNetworkEvaluationJob = serviceScope.launch {
            delay(MOBILE_NETWORK_DEBOUNCE_MS)
            evaluateMobileServerPolicy(network)
        }
    }

    private suspend fun evaluateMobileServerPolicy(candidateNetwork: Network?) =
        mobileEvaluationMutex.withLock {
            val generation = serviceCommands.connectionGeneration ?: return@withLock
            val network = candidateNetwork ?: return@withLock
            val server = connectionMutex.withLock {
                currentServer?.takeIf {
                    serviceCommands.permitsAutomaticConnection(generation) && pendingRegularNetwork == null &&
                        coreRunning && !lockdownActive &&
                        it.engine != TunnelEngineKind.LEVIK_GAME
                }
            } ?: return@withLock

            val probed = try {
                container.whitelistDetector.detect(network, forceRefresh = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                WhitelistMode.UNKNOWN
            }
            // Leaving the mobile server already failed on this network: one "open" answer is not enough.
            val mode = if (probed == WhitelistMode.INACTIVE && allowlistHeld(network)) WhitelistMode.UNKNOWN else probed
            if (!serviceCommands.permitsAutomaticConnection(generation) || networkMonitor.activeNetwork() != network) return@withLock
            lastProbeMode = mode
            VpnStateStore.update(coreOwner) { snapshot ->
                snapshot.copy(whitelistMode = mode)
            }
            if (mode == WhitelistMode.UNKNOWN) return@withLock
            lastWhitelistMode = mode
            // Allowlists went off: the next time they come on, they move the user again.
            if (mode == WhitelistMode.INACTIVE) allowlistOverride = false

            val returnServerId = readSecureString(SecureFileStore.ALLOWLIST_RETURN_SERVER)
            val decision = mobileSwitchPolicy.evaluate(
                automaticServer = container.settings.automaticServer.value,
                currentServerIsMobile = server.isMobileServer(),
                physicalNetworkIsCellular = networkMonitor.isCellular(network),
                whitelistMode = mode,
                networkIdentity = network.toString(),
                nowMs = SystemClock.elapsedRealtime(),
                returnPending = returnServerId != null,
                userOverride = allowlistOverride,
            )
            if (decision == MobileServerSwitchDecision.NONE) return@withLock
            if (networkMonitor.activeNetwork() != network) return@withLock

            val profile = runCatching { readPreparedProfile() }.getOrNull() ?: return@withLock
            val target = when (decision) {
                MobileServerSwitchDecision.TO_MOBILE -> {
                    allowlistLadder.reset()
                    allowlistTarget(profile, server, network)
                }
                MobileServerSwitchDecision.TO_REGULAR -> returnTarget(profile, returnServerId, network)
                MobileServerSwitchDecision.NONE -> null
            } ?: return@withLock
            val accepted = connectionMutex.withLock {
                serviceCommands.permitsAutomaticConnection(generation) && pendingRegularNetwork == null &&
                    coreRunning && currentServer?.id == server.id
            }
            if (!accepted) return@withLock

            if (server.isMobileServer()) {
                allowlistLadder.reset()
            } else {
                container.secureStore.put(SecureFileStore.LAST_REGULAR_SERVER, server.id.encodeToByteArray())
                // Where the user returns when allowlists end, whether Auto chose it or they did.
                container.secureStore.put(SecureFileStore.ALLOWLIST_RETURN_SERVER, server.id.encodeToByteArray())
            }
            container.secureStore.put(
                SecureFileStore.SELECTED_SERVER,
                target.id.encodeToByteArray(),
            )
            automaticRollbackServerId = server.id
            automaticTargetServerId = target.id
            allowlistOffSource = if (decision == MobileServerSwitchDecision.TO_REGULAR) server.id to network else null
            val reason = if (decision == MobileServerSwitchDecision.TO_MOBILE) {
                SmartSwitchReason.ALLOWLIST_ON
            } else {
                SmartSwitchReason.ALLOWLIST_OFF
            }
            container.appPlatform.smartAuto.reportSwitch(
                SmartSwitch(server.id, server.smartProtocol(), target.id, target.smartProtocol(), reason),
            )
            AppLogger.i(
                LOG_TAG,
                "Carrier policy changed; automatically switching server class",
            )
            nextAttemptCause = AttemptCause.NETWORK_CHANGE
            connectionJob?.cancel()
            connectionJob = serviceScope.launch {
                stopConnection(stopService = false)
                connect()
            }
        }

    private fun allowlistHeld(network: Network): Boolean =
        allowlistHold?.let { (held, untilMs) -> held == network && SystemClock.elapsedRealtime() < untilMs } == true

    /**
     * Allowlists seemed to end and the app left a mobile server, but the regular
     * server carries nothing on the same mobile network: the probe was wrong.
     * Back to the mobile server, which this network then keeps for a while.
     */
    private suspend fun returnAfterFalseAllowlistEnd(current: TunnelServer, network: Network): Boolean {
        val (sourceId, sourceNetwork) = allowlistOffSource ?: return false
        allowlistOffSource = null
        if (sourceNetwork != network || !networkMonitor.isCellular(network)) return false
        val source = runCatching { readPreparedProfile() }.getOrNull()?.servers
            ?.firstOrNull { it.id == sourceId && it.isMobileServer() } ?: return false
        val accepted = connectionMutex.withLock {
            coreRunning && currentServer?.id == current.id
        }
        if (!accepted) return false
        allowlistHold = network to SystemClock.elapsedRealtime() + ALLOWLIST_HOLD_MS
        lastWhitelistMode = WhitelistMode.ACTIVE
        pendingAutoFallback = null
        container.secureStore.put(SecureFileStore.ALLOWLIST_RETURN_SERVER, current.id.encodeToByteArray())
        container.secureStore.put(SecureFileStore.SELECTED_SERVER, source.id.encodeToByteArray())
        container.appPlatform.smartAuto.reportSwitch(
            SmartSwitch(current.id, current.smartProtocol(), source.id, source.smartProtocol(), SmartSwitchReason.ALLOWLIST_ON),
        )
        AppLogger.w(LOG_TAG, "Allowlists: the regular server carries nothing on this mobile network; back to the mobile server")
        VpnStateStore.update(coreOwner) { it.copy(whitelistMode = WhitelistMode.ACTIVE) }
        nextAttemptCause = AttemptCause.NETWORK_CHANGE
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopConnection(stopService = false)
            connect()
        }
        return true
    }

    /** Whether the app itself moved this connection for allowlists, or Auto may. */
    private fun allowlistSessionActive(): Boolean =
        container.settings.automaticServer.value ||
            readSecureString(SecureFileStore.ALLOWLIST_RETURN_SERVER) != null

    /**
     * The mobile server to try under allowlists: Smart Auto's best among those
     * not tried yet, where the one that last carried traffic on this network
     * leads its group (signed-in relays, ordinary servers, other relays).
     */
    private suspend fun allowlistTarget(
        profile: PreparedTunnelProfile,
        current: TunnelServer,
        network: Network?,
    ): TunnelServer? {
        val nowMs = SystemClock.elapsedRealtime()
        val tried = allowlistLadder.ids()
        val pool = profile.servers.filter { server ->
            server.id != current.id && server.id !in tried && isAllowlistCandidate(server, nowMs)
        }
        if (pool.isEmpty()) return null
        val ranked = container.appPlatform.smartAuto.rankAllowlist(pool, measureLatencies(pool), network)
        return ranked.firstNotNullOfOrNull { item -> pool.firstOrNull { it.id == item.id } }
    }

    private fun isAllowlistCandidate(server: TunnelServer, nowMs: Long): Boolean =
        server.isMobileServer() &&
            !server.isHomeRoadServer() &&
            !container.appPlatform.underMaintenance(server) &&
            tunnelHealthPolicy.isCandidateEligible(server.id, nowMs) &&
            // Yandex needs the user's own sign-in; without it the server cannot start.
            (server.engine != TunnelEngineKind.LEVIK_YANDEX || server.hasUsableYandexAuth())

    /**
     * Where to go when allowlists end: the server used before them unless it
     * is gone or under maintenance, else Smart Auto's best regular server.
     */
    private suspend fun returnTarget(
        profile: PreparedTunnelProfile,
        returnServerId: String?,
        network: Network?,
    ): TunnelServer? {
        val saved = (returnServerId ?: readSecureString(SecureFileStore.LAST_REGULAR_SERVER))
            ?.let { id -> profile.servers.firstOrNull { it.id == id } }
            ?.takeIf { !it.isMobileServer() && !container.appPlatform.underMaintenance(it) }
        if (saved != null) return saved
        val pool = profile.servers.filter { it.isSmartAutoCandidate() && !container.appPlatform.underMaintenance(it) }
        if (pool.isEmpty()) return null
        val measured = measureLatencies(pool)
        return container.appPlatform.smartAuto.rank(pool, measured, network).firstNotNullOfOrNull { item ->
            pool.firstOrNull { it.id == item.id }
                ?.takeIf { measured[it.id] != null || !ServerPinger.silenceMeansDown(it) }
        }
    }

    /** The next mobile server after [failed] carried nothing under allowlists; null when none is left. */
    private suspend fun advanceAllowlist(failed: TunnelServer, network: Network?, requireRunning: Boolean): TunnelServer? {
        allowlistLadder.tried(failed.id, failed.smartProtocol())
        if (allowlistLadder.exhausted()) {
            AppLogger.w(LOG_TAG, "Allowlists: no mobile server carried traffic; giving up for now")
            return null
        }
        val profile = runCatching { readPreparedProfile() }.getOrNull() ?: return null
        val target = allowlistTarget(profile, failed, network) ?: run {
            AppLogger.w(LOG_TAG, "Allowlists: no other mobile server to try")
            return null
        }
        val accepted = !requireRunning || connectionMutex.withLock {
            coreRunning && currentServer?.id == failed.id
        }
        if (!accepted) return null
        allowlistLadder.climbed()
        pendingAutoFallback = null
        container.secureStore.put(SecureFileStore.SELECTED_SERVER, target.id.encodeToByteArray())
        automaticTargetServerId = target.id
        container.appPlatform.smartAuto.reportSwitch(
            SmartSwitch(failed.id, failed.smartProtocol(), target.id, target.smartProtocol(), SmartSwitchReason.ALLOWLIST_NEXT),
        )
        AppLogger.i(LOG_TAG, "Allowlists: ${failed.smartProtocol()} server carries nothing; trying ${target.smartProtocol()}")
        telemetry.record { it.recovery(RecoveryAction.FAILOVER) }
        nextAttemptCause = AttemptCause.FAILOVER
        return target
    }

    /** Every mobile server failed to start: back to the server used before allowlists. */
    private fun rollBackAllowlistSwitch() {
        val rollbackServerId = automaticRollbackServerId ?: return
        automaticRollbackServerId = null
        automaticTargetServerId = null
        container.secureStore.put(SecureFileStore.SELECTED_SERVER, rollbackServerId.encodeToByteArray())
        telemetry.record { it.recovery(RecoveryAction.ROLLBACK) }
        nextAttemptCause = AttemptCause.ROLLBACK
    }

    private fun readSecureString(key: String): String? {
        val bytes = runCatching { container.secureStore.get(key) }.getOrNull() ?: return null
        return try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun onNetworkAvailable(network: Network) {
        if (destroyed.get()) return
        // Capability updates repeat this callback; only a newly usable network is an event.
        if (telemetryNetworks.add(network)) {
            telemetry.record { it.network(networkType(network), NetworkState.AVAILABLE) }
        }
        scheduleMobilePolicyEvaluation()
        handleUnderlyingNetworkChange()
    }

    private fun onNetworkLost(network: Network) {
        if (destroyed.get()) return
        if (telemetryNetworks.remove(network)) {
            telemetry.record { it.network(NetworkType.UNKNOWN, NetworkState.LOST) }
        }
        scheduleMobilePolicyEvaluation()
        handleUnderlyingNetworkChange()
    }

    private fun handleUnderlyingNetworkChange() {
        val generation = serviceCommands.connectionGeneration ?: return
        serviceScope.launch {
            if (!serviceCommands.permitsAutomaticConnection(generation)) return@launch
            val requirement = currentServer?.networkRequirement ?: return@launch
            val preferred = networkMonitor.activeNetwork()
            val returningToRegular = preferred != null && shouldReturnToRegularNetwork(preferred)
            if (returningToRegular && pendingRegularNetwork == preferred && connectionJob?.isActive == true) return@launch
            val selected = networkMonitor.activeNetwork(requirement)
            val selectedDns = selected?.takeIf { currentServer?.engine == TunnelEngineKind.XRAY }
                ?.let(networkMonitor::bootstrapDnsServer)
            if (!returningToRegular && selected == currentNetwork && selectedDns == currentBootstrapDnsServer &&
                (coreRunning || reconnectJob?.isActive == true)
            ) {
                return@launch
            }
            // Interrupt an in-flight handshake before waiting for the connection lock. Otherwise
            // a lost Wi-Fi network can leave the old handshake blocking the LTE callback.
            serviceCommands.interruptReconnect(generation)
            connectionMutex.withLock {
                if (destroyed.get() || !serviceCommands.permitsAutomaticConnection(generation)) return@withLock
                val server = currentServer ?: return@withLock
                val preferredNetwork = networkMonitor.activeNetwork()
                if (preferredNetwork != null && shouldReturnToRegularNetwork(preferredNetwork)) {
                    if (pendingRegularNetwork != preferredNetwork || connectionJob?.isActive != true) {
                        pendingRegularNetwork = preferredNetwork
                        mobileNetworkEvaluationJob?.cancel()
                        mobileAutomationJob?.cancel()
                        smartLadder.reset()
                        allowlistLadder.reset()
                        mobileSwitchPolicy.reset()
                        allowlistOverride = false
                        allowlistOffSource = null
                        allowlistHold = null
                        lastWhitelistMode = WhitelistMode.INACTIVE
                        lastProbeMode = WhitelistMode.INACTIVE
                        pendingAutoFallback = null
                        automaticRollbackServerId = null
                        automaticTargetServerId = null
                        leaveCellularOnlyServer(server, preferredNetwork)
                    }
                    return@withLock
                }
                val replacement = networkMonitor.activeNetwork(server.networkRequirement)
                val replacementDns = replacement?.takeIf { server.engine == TunnelEngineKind.XRAY }
                    ?.let(networkMonitor::bootstrapDnsServer)
                if (replacement == currentNetwork && replacementDns == currentBootstrapDnsServer && coreRunning) {
                    return@withLock
                }
                if (replacement != null && !setUnderlyingNetworks(arrayOf(replacement))) {
                    return@withLock
                }
                currentNetwork = replacement
                currentBootstrapDnsServer = replacementDns
                underlyingNetwork.set(replacement)
                tunnelHealthPolicy.onUnderlyingNetworkChanged(SystemClock.elapsedRealtime())
                smartLadder.reset()
                allowlistLadder.reset()
                mobileSwitchPolicy.reset()
                allowlistOverride = false
                allowlistOffSource = null
                lastWhitelistMode = WhitelistMode.UNKNOWN
                if (replacement != null) {
                    AppLogger.i(LOG_TAG, "Underlying network changed, triggering reconnect")
                    telemetry.record { it.network(networkType(replacement), NetworkState.CHANGED) }
                    if (moveToNetworkWinner(server, replacement)) return@withLock
                    scheduleReconnectLocked(AttemptCause.NETWORK_CHANGE)
                    return@withLock
                }
                val otherNetwork = networkMonitor.activeNetwork()
                    ?.takeIf { server.networkRequirement != TunnelNetworkRequirement.ANY }
                if (otherNetwork != null) {
                    leaveCellularOnlyServer(server, otherNetwork)
                    return@withLock
                }
                VpnStateStore.update(coreOwner) { state ->
                    state.copy(
                        state = VpnConnectionState.RECONNECTING,
                        downloadBytesPerSecond = 0,
                        uploadBytesPerSecond = 0,
                    )
                }
                showForeground(VpnConnectionState.RECONNECTING, currentServerName)
            }
        }
    }

    private fun shouldReturnToRegularNetwork(network: Network): Boolean = shouldReturnFromMobileServer(
        currentServerIsMobile = currentServer?.isMobileServer() == true,
        preferredNetworkIsCellular = networkMonitor.isCellular(network),
        preferredNetworkIsValidated = networkMonitor.isValidated(network),
        automaticServer = container.settings.automaticServer.value,
        returnPending = readSecureString(SecureFileStore.ALLOWLIST_RETURN_SERVER) != null,
    )

    /**
     * A validated Wi-Fi network can take over an automatic mobile session even while the relay
     * retains cellular. Also recovers a mobile-only server when cellular disappears entirely.
     * The previous regular server takes over, else Smart Auto's best.
     */
    private fun leaveCellularOnlyServer(current: TunnelServer, network: Network) {
        reconnectJob?.cancel()
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            val generation = serviceCommands.connectionGeneration ?: return@launch
            val validatedHandover = pendingRegularNetwork == network
            val profile = runCatching { readPreparedProfile() }.getOrNull()
            val target = profile?.let {
                returnTarget(it, readSecureString(SecureFileStore.ALLOWLIST_RETURN_SERVER), network)
            }
            coroutineContext.ensureActive()
            if (!serviceCommands.permitsAutomaticConnection(generation)) return@launch
            if (networkMonitor.activeNetwork() != network ||
                (validatedHandover && !shouldReturnToRegularNetwork(network))
            ) {
                if (pendingRegularNetwork == network) pendingRegularNetwork = null
                if (validatedHandover) {
                    // Wi-Fi vanished during ranking; the retained mobile carrier is still needed.
                    startMobileServerAutomation()
                    handleUnderlyingNetworkChange()
                }
                return@launch
            }
            if (target == null) {
                AppLogger.w(LOG_TAG, "Mobile data is gone and no regular server can take over")
                VpnStateStore.set(
                    coreOwner,
                    VpnSnapshot(
                        state = VpnConnectionState.ERROR,
                        failure = VpnFailure.NETWORK_REQUIREMENT,
                    ),
                )
                stopConnection(stopService = false, preserveError = true)
                val enteredLockdown = enterKillSwitchLockdown(null)
                endTelemetryAfterFailure(enteredLockdown, "cellular_required")
                if (!enteredLockdown) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            container.secureStore.put(SecureFileStore.SELECTED_SERVER, target.id.encodeToByteArray())
            container.appPlatform.smartAuto.reportSwitch(
                SmartSwitch(current.id, current.smartProtocol(), target.id, target.smartProtocol(), SmartSwitchReason.ALLOWLIST_OFF),
            )
            AppLogger.i(LOG_TAG, "Leaving mobile server for the preferred physical network")
            nextAttemptCause = AttemptCause.NETWORK_CHANGE
            stopConnection(stopService = false)
            connect()
        }
    }

    /**
     * Smart Auto: a network where another server carried traffic last time
     * reconnects straight to it, without measuring every server first.
     */
    private fun moveToNetworkWinner(current: TunnelServer, network: Network): Boolean {
        if (!container.settings.automaticServer.value || !current.isSmartAutoCandidate()) return false
        val smartAuto = container.appPlatform.smartAuto
        val winnerId = smartAuto.lastWinner(network)?.takeIf { it != current.id } ?: return false
        val profile = runCatching { readPreparedProfile() }.getOrNull() ?: return false
        val winner = profile.servers.firstOrNull { it.id == winnerId }
            ?.takeIf { it.isSmartAutoCandidate() && !container.appPlatform.underMaintenance(it) }
            ?: return false
        pendingAutoFallback = null
        container.secureStore.put(SecureFileStore.SELECTED_SERVER, winner.id.encodeToByteArray())
        smartAuto.reportSwitch(
            SmartSwitch(current.id, current.smartProtocol(), winner.id, winner.smartProtocol(), SmartSwitchReason.NETWORK_WINNER),
        )
        AppLogger.i(LOG_TAG, "Smart Auto: ${winner.smartProtocol()} carried traffic on this network last time; reconnecting there")
        nextAttemptCause = AttemptCause.NETWORK_CHANGE
        reconnectJob?.cancel()
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopConnection(stopService = false)
            connect()
        }
        return true
    }

    private fun scheduleReconnect(cause: AttemptCause) {
        val generation = serviceCommands.connectionGeneration ?: return
        if (destroyed.get()) return
        serviceScope.launch {
            connectionMutex.withLock {
                if (destroyed.get() || !serviceCommands.permitsAutomaticConnection(generation)) return@withLock
                scheduleReconnectLocked(cause)
            }
        }
    }

    private fun scheduleReconnectLocked(cause: AttemptCause) {
        val generation = serviceCommands.connectionGeneration ?: return
        if (!serviceCommands.permitsAutomaticConnection(generation)) return
        reconnectJob?.cancel()
        val reconnect = serviceScope.launch(start = CoroutineStart.LAZY) {
            delay(RECONNECT_DEBOUNCE_MS)
            connectionMutex.withLock {
                if (destroyed.get() || !serviceCommands.permitsAutomaticConnection(generation)) return@withLock
                if (!coreRunning && tunInterface == null) return@withLock
                val originalRequest = currentEngineRequest ?: return@withLock
                val engine = currentEngine ?: return@withLock
                val originalServer = currentServer ?: return@withLock
                val previousPrepared = currentPreparedSession
                val previousExpiryJob = profileExpiryJob
                val previousProfileDeadline = activeProfileDeadline
                VpnStateStore.update(coreOwner) {
                    it.copy(state = VpnConnectionState.RECONNECTING)
                }
                showForeground(VpnConnectionState.RECONNECTING, currentServerName)
                telemetry.record { it.attempt(originalServer.name, originalServer.telemetryProtocol(), cause) }
                val progress = AttemptProgress(AttemptStage.CORE, "core_start_failed")
                try {
                    val refreshedProfile = if (originalServer.engine == TunnelEngineKind.LEVIK_RELAY) {
                        progress.reach(AttemptStage.PROFILE, "relay_access_refresh")
                        container.repository.prepareRelayConnection(requireNotNull(currentSubscriptionId))
                    } else null
                    val server = refreshedProfile?.servers?.firstOrNull {
                        it.id == originalServer.id && it.engine == TunnelEngineKind.LEVIK_RELAY
                    } ?: if (refreshedProfile == null) originalServer else error("Selected relay server is unavailable")
                    val request = if (originalRequest is TunnelEngineRequest.Relay) {
                        val replacement = requireNotNull(server.relayConfig)
                        require(relayCredentialHasSameBinding(originalRequest.config, replacement)) {
                            "Relay credential binding changed"
                        }
                        originalRequest.copy(config = replacement)
                    } else originalRequest
                    val relayCredentialRenewed = originalRequest is TunnelEngineRequest.Relay &&
                        request is TunnelEngineRequest.Relay &&
                        relayCredentialExtendsAccess(originalRequest.config, request.config)
                    val relayDeadline = if (request is TunnelEngineRequest.Relay) {
                        if (relayCredentialRenewed) {
                            val expiry = listOfNotNull(
                                refreshedProfile?.subscriptionExpiresAt,
                                request.config.bootstrap.expiresAt,
                            ).minOf { Instant.parse(it) }
                            MonotonicCredentialDeadline.create(expiry, Instant.now(), SystemClock.elapsedRealtime())
                        } else previousProfileDeadline
                    } else null
                    if (request is TunnelEngineRequest.Relay) checkConnectionDeadline(relayDeadline)
                    currentServer = server
                    currentEngineRequest = request
                    yandexRefreshJob?.cancel()
                    yandexRefreshJob = null
                    relayEntitlementWatchdogJob?.cancel()
                    relayEntitlementWatchdogJob = null
                    autoHealingJob?.cancel()
                    autoHealingJob = null
                    serviceRouteRuntime?.close()
                    serviceRouteRuntime = null
                    engine.stop(coreOwner, previousPrepared, coreLease)
                    coreRunning = false
                    coreLease = null
                    currentPreparedSession = null
                    if (relayCredentialRenewed) {
                        // The old transport is closed. Its queued expiry must not tear down
                        // a fresh credential while VK login waits for the new admission.
                        previousExpiryJob?.cancel()
                        if (profileExpiryJob === previousExpiryJob) profileExpiryJob = null
                    }
                    check(!destroyed.get()) { "VPN service was destroyed during reconnect" }
                    progress.reach(AttemptStage.TUN, "unreachable")
                    val network = currentNetwork
                        ?: throw NetworkSetupException("No usable underlying network")
                    enforceNetworkRequirement(server, network)
                    underlyingNetwork.set(network)
                    val bootstrapDnsServer = resolveBootstrapDnsServer(server, network)
                    currentBootstrapDnsServer = bootstrapDnsServer
                    progress.reach(startStage(server), "core_start_failed")
                    val engineAttempt = Any()
                    val prepared = engine.prepare(
                        owner = coreOwner,
                        request = request,
                        environment = TunnelEngineEnvironment(
                            network = network,
                            protector = fileDescriptorProtector(network, server),
                            dnsServer = bootstrapDnsServer,
                            unboundSocketProtector = { fd -> protectUnboundTunnelSocket(fd, ::protect) },
                            terminalFailureHandler = { code -> onTunnelEngineTerminalFailure(code, engineAttempt) },
                        ),
                    )
                    val acceptedPrepared = lifecycleGate.withLock {
                        if (destroyed.get() || !coroutineContext.isActive || !serviceCommands.permitsConnection(generation)) {
                            false
                        } else {
                            currentPreparedSession = prepared
                            currentEngineAttempt = engineAttempt
                            true
                        }
                    }
                    if (!acceptedPrepared) {
                        runCatching { engine.stop(coreOwner, prepared) }
                        coroutineContext.ensureActive()
                        return@withLock
                    }
                    progress.reach(AttemptStage.TUN, "tun_failed")
                    val previousTun = tunInterface
                        ?: throw NetworkSetupException("VPN interface is unavailable")
                    val previousTunPlan = previousPrepared?.tunPlan ?: when (request) {
                        is TunnelEngineRequest.Xray -> request.tunPlan
                        is TunnelEngineRequest.Relay -> request.tunPlan
                        is TunnelEngineRequest.Yandex -> request.tunPlan
                        is TunnelEngineRequest.Tuic -> request.tunPlan
                        is TunnelEngineRequest.Game -> request.tunPlan
                        is TunnelEngineRequest.Dnstt -> request.tunPlan
                    }
                    val activeTun = if (prepared.tunPlan == previousTunPlan) {
                        previousTun
                    } else {
                        val replacement = establishTun(
                            serverName = server.name,
                            tunPlan = prepared.tunPlan,
                            gamePackages = gamePackagesFor(server),
                        )
                        val acceptedReplacement = lifecycleGate.withLock {
                            if (destroyed.get()) {
                                false
                            } else {
                                tunInterface = replacement
                                true
                            }
                        }
                        if (!acceptedReplacement) {
                            runCatching { replacement.close() }
                            return@withLock
                        }
                        runCatching { previousTun.close() }
                        replacement
                    }
                    if (!setUnderlyingNetworks(arrayOf(network))) {
                        throw NetworkSetupException(
                            "Unable to bind the VPN to its underlying network",
                        )
                    }
                    progress.reach(startStage(server), "core_start_failed")
                    coreLease = engine.start(
                        owner = coreOwner,
                        prepared = prepared,
                        tun = AndroidTunnelFileDescriptorHandle(activeTun),
                    )
                    coroutineContext.ensureActive()
                    if (request is TunnelEngineRequest.Relay) checkConnectionDeadline(relayDeadline)
                    coreRunning = true
                    startServiceRouteRuntime()
                    tunnelHealthPolicy.onTunnelStarted(SystemClock.elapsedRealtime())
                    relayDeadline?.let(::scheduleProfileExpiry)
                    if (request is TunnelEngineRequest.Yandex) {
                        val config = request.config
                        scheduleProfileExpiry(MonotonicCredentialDeadline.create(
                            Instant.ofEpochSecond(config.expiresAt),
                            Instant.now(), SystemClock.elapsedRealtime(),
                        ))
                        admittedYandexAuth = config.bootstrap?.providerAuth
                    }
                    val published = lifecycleGate.withLock {
                        if (destroyed.get() || !serviceCommands.permitsConnection(generation)) return@withLock false
                        VpnStateStore.update(coreOwner) {
                            it.copy(state = VpnConnectionState.CONNECTED, failure = null)
                        }
                        // The reconnect cancelled the health probes; without them a tunnel
                        // that dies later stays up until the user reconnects by hand.
                        startAutoHealing()
                        startRelayEntitlementWatchdog(server, prepared)
                        startYandexRefresh(prepared)
                        showForeground(VpnConnectionState.CONNECTED, currentServerName)
                        true
                    }
                    if (!published) {
                        stopCoreAndTun()
                        return@withLock
                    }
                    telemetry.record { it.connected() }
                    telemetry.flushSoon()
                    AppLogger.i(LOG_TAG, "VPN successfully reconnected")
                    // A new network may block what worked on the old one, and a reconnect
                    // after failed probes must prove the server carries traffic again.
                    startTrafficCheck(server, network)
                } catch (error: CancellationException) {
                    engine.stop(coreOwner, currentPreparedSession, coreLease)
                    coreRunning = false
                    coreLease = null
                    currentPreparedSession = null
                    throw error
                } catch (error: Throwable) {
                    coreRunning = false
                    networkMonitor.stop(releaseCellular = false)
                    statsJob?.cancel()
                    statsJob = null
                    autoHealingJob?.cancel()
                    autoHealingJob = null
                    yandexRefreshJob?.cancel()
                    yandexRefreshJob = null
                    relayEntitlementWatchdogJob?.cancel()
                    relayEntitlementWatchdogJob = null
                    stopCoreAndTun()
                    coroutineContext.ensureActive()
                    if (!serviceCommands.permitsConnection(generation)) return@withLock
                    AppLogger.e(LOG_TAG, "VPN reconnect failed", error)
                    val (failedStage, failedCode) = progress.failure(error)
                    telemetry.record { it.attemptFailed(failedStage, failedCode) }
                    val detail = if (error is ApiException) {
                        relayAccessFailureDetail(error)
                    } else if (error is TunnelEngineFailureException) {
                        relayFailureDetail(error.code)
                    } else {
                        error.message?.takeIf { it.isNotBlank() }?.take(MAX_FAILURE_DETAIL_LENGTH)
                    }
                    VpnStateStore.update(coreOwner) {
                        it.copy(
                            state = VpnConnectionState.ERROR,
                            failure = when (error) {
                                is TunnelNetworkRequirementException ->
                                    VpnFailure.NETWORK_REQUIREMENT
                                is TunnelEngineUnavailableException -> VpnFailure.CORE_UNAVAILABLE
                                is TunnelEngineFailureException -> when (error.code) {
                                    "relay_native_missing", "relay_process_start_failed", "game_native_missing" ->
                                        VpnFailure.CORE_UNAVAILABLE
                                    "relay_credential_expired" -> VpnFailure.INVALID_PROFILE
                                    else -> VpnFailure.NETWORK
                                }
                                else -> VpnFailure.NETWORK
                            },
                            failureDetail = detail,
                        )
                    }
                    val enteredLockdown = enterKillSwitchLockdownLocked(detail)
                    endTelemetryAfterFailure(enteredLockdown, failedCode)
                    if (!enteredLockdown) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        reconnectJob = reconnect
        serviceCommands.trackReconnect(generation, reconnect)
        reconnect.start()
    }

    private fun scheduleProfileExpiry(deadline: MonotonicCredentialDeadline) {
        profileExpiryJob?.cancel()
        activeProfileDeadline = deadline
        profileExpiryJob = serviceScope.launch {
            while (true) {
                val remainingMs = deadline.remainingMillis(SystemClock.elapsedRealtime())
                if (remainingMs <= 0L) break
                delay(remainingMs)
            }
            profileExpiryJob = null
            activeProfileDeadline = null
            admittedYandexAuth = null
            AppLogger.w(LOG_TAG, "Connection authorization expired, tearing down tunnel")
            VpnStateStore.set(
                coreOwner,
                VpnSnapshot(
                    state = VpnConnectionState.ERROR,
                    failure = VpnFailure.INVALID_PROFILE,
                ),
            )
            stopConnection(stopService = false, preserveError = true)
            runCatching { container.trafficHistoryStore.flush() }
            val enteredLockdown = enterKillSwitchLockdown(null)
            endTelemetryAfterFailure(enteredLockdown, "auth_deadline")
            if (!enteredLockdown) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startYandexRefresh(prepared: PreparedTunnelEngineSession) {
        yandexRefreshJob?.cancel()
        yandexRefreshJob = null
        if (currentEngine?.kind != TunnelEngineKind.LEVIK_YANDEX) return
        yandexRefreshJob = serviceScope.launch {
            var attempted = false
            while (true) {
                val before = connectionMutex.withLock {
                    if (!coreRunning || currentPreparedSession !== prepared) return@launch
                    activeProfileDeadline ?: return@launch
                }
                val refreshDelay = yandexAuthRefreshDelayMillis(
                    before.remainingMillis(SystemClock.elapsedRealtime()), attempted,
                ) ?: return@launch
                delay(refreshDelay)
                if (before.isExpired(SystemClock.elapsedRealtime())) return@launch
                attempted = true
                val snapshot = connectionMutex.withLock {
                    if (!coreRunning || currentPreparedSession !== prepared) return@launch
                    YandexRefreshSnapshot(
                        currentServer ?: return@launch,
                        currentNetwork ?: return@launch,
                        currentSubscriptionId ?: return@launch,
                        currentEngine as? YandexRefreshableTunnelEngineAdapter ?: return@launch,
                    )
                }
                try {
                    val originalConfig = requireNotNull(snapshot.server.yandexConfig)
                    val original = originalConfig.bootstrap
                    val profile = if (originalConfig.telemost != null) {
                        container.repository.prepareTelemostTunnel(snapshot.subscriptionId, originalConfig.telemost.conferenceUrl, selectAfterPreparation = false)
                    } else {
                        val document = requireNotNull(original)
                        val auth = refreshYandexGuest(this@LevikVpnService, document.documentUrl, snapshot.network)
                        if (auth == null) {
                            AppLogger.w(LOG_TAG, "Yandex guest refresh unavailable; original deadline remains active")
                            continue
                        }
                        val stillCurrent = connectionMutex.withLock {
                            coreRunning && currentPreparedSession === prepared && currentNetwork == snapshot.network
                        }
                        if (!stillCurrent) return@launch
                        container.repository.prepareYandexTunnel(snapshot.subscriptionId, document.documentUrl, auth, selectAfterPreparation = false)
                    }
                    val refreshedServer = profile.servers.single { it.engine == TunnelEngineKind.LEVIK_YANDEX }
                    val config = requireNotNull(refreshedServer.yandexConfig)
                    if (originalConfig.telemost != null && config.expiresAt <= originalConfig.expiresAt + 30) return@launch
                    val refreshMode = yandexProfileRefreshMode(originalConfig, config)
                    if (refreshMode == YandexProfileRefreshMode.REJECT) {
                        requestRelayFailClosed(VpnFailure.INVALID_PROFILE, getString(R.string.relay_entitlement_revoked))
                        return@launch
                    }
                    val canRefresh = connectionMutex.withLock {
                        if (!coreRunning || currentPreparedSession !== prepared) return@withLock false
                        // Keep the verified server credential for a concurrent reconnect even
                        // if its network callback cancels this coroutine during the helper ACK.
                        // The old local expiry stays in force until the helper confirms admission.
                        currentServer = refreshedServer
                        currentEngineRequest = (currentEngineRequest as? TunnelEngineRequest.Yandex)?.copy(config = config)
                        if (refreshMode == YandexProfileRefreshMode.RECONNECT) scheduleReconnectLocked(AttemptCause.RECONNECT)
                        true
                    }
                    if (!canRefresh) return@launch
                    if (refreshMode == YandexProfileRefreshMode.RECONNECT) {
                        AppLogger.i(LOG_TAG, "Yandex session key renewed; reconnecting inside the VPN interface")
                        return@launch
                    }
                    if (!snapshot.adapter.refresh(coreOwner, prepared, config)) continue
                    val updated = connectionMutex.withLock {
                        if (!coreRunning || currentPreparedSession !== prepared) {
                            return@withLock false
                        }
                        scheduleProfileExpiry(MonotonicCredentialDeadline.create(
                            Instant.ofEpochSecond(config.expiresAt),
                            Instant.now(), SystemClock.elapsedRealtime(),
                        ))
                        admittedYandexAuth = config.bootstrap?.providerAuth
                        true
                    }
                    if (!updated) continue
                    AppLogger.i(LOG_TAG, "Yandex authorization refreshed and admitted by the active tunnel")
                    // No further extension is possible near subscription expiry.
                    if (requireNotNull(config.bootstrap).providerAuth.validUntil <= requireNotNull(original).providerAuth.validUntil + 30) return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val phase = when (error) {
                        is com.leviknet.vpn.core.network.ApiException.Network -> "api_network"
                        is com.leviknet.vpn.core.network.ApiException.Rejected -> "api_rejected"
                        is com.leviknet.vpn.core.network.ApiException.Unauthorized -> "api_session"
                        else -> "profile_validation"
                    }
                    AppLogger.w(LOG_TAG, "Yandex authorization refresh unavailable ($phase); original deadline remains active")
                }
            }
        }
    }

    private data class YandexRefreshSnapshot(
        val server: TunnelServer,
        val network: Network,
        val subscriptionId: String,
        val adapter: YandexRefreshableTunnelEngineAdapter,
    )

    private fun checkConnectionDeadline(deadline: MonotonicCredentialDeadline?) {
        if (deadline?.isExpired(SystemClock.elapsedRealtime()) == true) {
            throw TunnelEngineFailureException("relay_credential_expired")
        }
    }

    private fun startRelayEntitlementWatchdog(
        server: TunnelServer,
        prepared: PreparedTunnelEngineSession,
    ) {
        relayEntitlementWatchdogJob?.cancel()
        relayEntitlementWatchdogJob = null
        if (server.engine !in DOCUMENT_RELAY_ENGINES) {
            return
        }
        relayEntitlementWatchdogJob = serviceScope.launch {
            val subscriptionId = currentSubscriptionId
            if (subscriptionId == null) {
                requestRelayFailClosed(
                    VpnFailure.INVALID_PROFILE,
                    getString(R.string.relay_entitlement_revoked),
                )
                return@launch
            }
            while (true) {
                delay(RELAY_ENTITLEMENT_WATCHDOG_INTERVAL_MS)
                val stillCurrent = connectionMutex.withLock {
                    coreRunning &&
                        currentEngine?.kind in DOCUMENT_RELAY_ENGINES &&
                        currentPreparedSession === prepared
                }
                if (!stillCurrent) return@launch
                if (refreshRelayEntitlementActive(subscriptionId, server.engine) == false) {
                    requestRelayFailClosed(
                        VpnFailure.INVALID_PROFILE,
                        getString(R.string.relay_entitlement_revoked),
                    )
                    return@launch
                }
                if (server.engine != TunnelEngineKind.LEVIK_RELAY) continue
                val deadline = connectionMutex.withLock {
                    if (!coreRunning || currentPreparedSession !== prepared) return@launch
                    activeProfileDeadline
                } ?: continue
                if (!relayCredentialRenewalDue(deadline.remainingMillis(SystemClock.elapsedRealtime()))) continue
                try {
                    // Renew while the admitted tunnel still carries API traffic. The original
                    // monotonic deadline remains in force until the new token is admitted.
                    val profile = container.repository.prepareRelayConnection(subscriptionId, forceRefresh = true)
                    val renewed = profile.servers.firstOrNull {
                        it.id == server.id && it.engine == TunnelEngineKind.LEVIK_RELAY
                    } ?: error("Selected relay server is unavailable")
                    val originalConfig = requireNotNull(server.relayConfig)
                    val renewedConfig = requireNotNull(renewed.relayConfig)
                    if (!relayCredentialHasSameBinding(originalConfig, renewedConfig)) {
                        requestRelayFailClosed(VpnFailure.INVALID_PROFILE, getString(R.string.relay_entitlement_revoked))
                        return@launch
                    }
                    if (!relayCredentialExtendsAccess(originalConfig, renewedConfig)) continue
                    val scheduled = connectionMutex.withLock {
                        if (!coreRunning || currentPreparedSession !== prepared) return@withLock false
                        // Reconnect reloads the verified cache and keeps the same selected node.
                        // Do not extend activeProfileDeadline before the native handshake succeeds.
                        scheduleReconnectLocked(AttemptCause.RECONNECT)
                        true
                    }
                    if (scheduled) {
                        AppLogger.i(LOG_TAG, "Relay access renewed; reconnecting with current authorization")
                        return@launch
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ApiException.Unauthorized) {
                    requestRelayFailClosed(VpnFailure.INVALID_PROFILE, getString(R.string.relay_entitlement_revoked))
                    return@launch
                } catch (error: Throwable) {
                    if (isRelayAccessDenied(error)) {
                        requestRelayFailClosed(VpnFailure.INVALID_PROFILE, getString(R.string.relay_entitlement_revoked))
                        return@launch
                    }
                    AppLogger.w(LOG_TAG, "Relay access refresh unavailable; original deadline remains active")
                }
            }
        }
    }

    /** Null means the authoritative account check was temporarily unavailable. */
    private suspend fun refreshRelayEntitlementActive(subscriptionId: String, engine: TunnelEngineKind): Boolean? = try {
        val now = Instant.now()
        val subscription = container.repository.refreshAccount().subscriptions
            .firstOrNull { it.uuid == subscriptionId }
        subscription?.isActiveAt(now) == true &&
            (if (engine == TunnelEngineKind.LEVIK_YANDEX) subscription.capabilities.yandexRelay
            else subscription.capabilities.whitelistRelay)
    } catch (error: CancellationException) {
        throw error
    } catch (_: ApiException.Unauthorized) {
        false
    } catch (_: Throwable) {
        null
    }

    private fun onTunnelEngineTerminalFailure(code: String, engineAttempt: Any) {
        if (!code.matches(Regex("^[a-z0-9_]{1,96}$"))) return
        AppLogger.w(LOG_TAG, "Relay engine terminated with stable code: $code")
        val failure = if (code == "relay_credential_expired") {
            VpnFailure.INVALID_PROFILE
        } else {
            VpnFailure.NETWORK
        }
        requestRelayFailClosed(failure, relayFailureDetail(code), engineAttempt)
    }

    private fun requestRelayFailClosed(
        failure: VpnFailure,
        detail: String,
        expectedEngineAttempt: Any? = null,
    ) {
        val generation = serviceCommands.connectionGeneration ?: return
        serviceScope.launch {
            connectionMutex.withLock {
                if (!serviceCommands.permitsAutomaticConnection(generation) ||
                    (expectedEngineAttempt != null && currentEngineAttempt !== expectedEngineAttempt) ||
                    !coreRunning || (currentEngine?.kind !in DOCUMENT_RELAY_ENGINES && currentEngine?.kind != TunnelEngineKind.LEVIK_DNSTT)
                ) {
                    return@withLock
                }
                reconnectJob?.cancel()
                reconnectJob = null
                statsJob?.cancel()
                statsJob = null
                profileExpiryJob?.cancel()
                profileExpiryJob = null
                activeProfileDeadline = null
                admittedYandexAuth = null
                autoHealingJob?.cancel()
                autoHealingJob = null
                yandexRefreshJob?.cancel()
                yandexRefreshJob = null
                relayEntitlementWatchdogJob?.cancel()
                relayEntitlementWatchdogJob = null
                networkMonitor.stop(releaseCellular = false)
                VpnStateStore.set(
                    coreOwner,
                    VpnSnapshot(
                        state = VpnConnectionState.ERROR,
                        engine = currentServer?.engine,
                        serverId = currentServer?.id,
                        serverName = currentServerName,
                        serverCountryCode = currentServer?.countryCode,
                        effectiveRoutingProfile = currentServer?.effectiveRoutingProfile(container.settings.routingPreset.value)
                            ?: EffectiveRoutingProfile.USER_SELECTED,
                        failure = failure,
                        failureDetail = detail.take(MAX_FAILURE_DETAIL_LENGTH),
                    ),
                )
                stopCoreAndTun()
                val enteredLockdown = enterKillSwitchLockdownLocked(detail)
                endTelemetryAfterFailure(enteredLockdown, "relay_terminal")
                if (!enteredLockdown) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun relayFailureDetail(code: String): String = when (code) {
        "relay_credential_expired" -> getString(R.string.relay_credential_expired)
        "relay_vk_auth_timeout" -> getString(R.string.relay_auth_timeout)
        "relay_vk_auth_cancelled" -> getString(R.string.relay_auth_cancelled)
        "relay_vk_auth_failed", "relay_vk_auth_network_unavailable" -> getString(R.string.relay_auth_failed)
        "relay_native_missing", "relay_process_start_failed" ->
            getString(R.string.relay_native_unavailable)
        "game_no_apps" -> getString(R.string.game_no_apps)
        "game_native_missing" -> getString(R.string.game_native_unavailable)
        else -> if (code.startsWith("game_")) getString(R.string.game_runtime_stopped) else getString(R.string.relay_runtime_stopped)
    }

    private fun relayAccessFailureDetail(error: ApiException): String = getString(when (error) {
        is ApiException.Network -> R.string.problem_network_body
        is ApiException.Unauthorized -> R.string.problem_session_body
        is ApiException.Rejected -> if (error.retryable) R.string.problem_service_body else R.string.relay_entitlement_revoked
        else -> R.string.profile_unavailable
    })

    /**
     * Engages the app-level Kill Switch: re-establishes the TUN and routes every
     * non-local packet into a blackhole so nothing leaks while the tunnel is down.
     * Must not be called while holding [connectionMutex]; the Locked variant is
     * for callers that already hold it.
     */
    private suspend fun enterKillSwitchLockdown(failureDetail: String?): Boolean =
        connectionMutex.withLock {
            if (destroyed.get()) return@withLock false
            enterKillSwitchLockdownLocked(failureDetail)
        }

    private suspend fun enterKillSwitchLockdownLocked(failureDetail: String?): Boolean {
        if (lockdownActive) return true
        if (!container.settings.killSwitchEnabled.value) return false
        return try {
            stopCoreAndTun()
            val dnsProvider = DnsProvider.CLOUDFLARE
            val request = TunnelEngineRequest.Xray(
                configFactory = XrayConfigFactory { tunFileDescriptor ->
                    XrayConfigBuilder(container.json).buildKillSwitchConfig(tunFileDescriptor)
                },
                tunPlan = xrayTunPlan(
                    primaryDns = dnsProvider.primaryIpv4,
                    secondaryDns = dnsProvider.secondaryIpv4,
                ),
            )
            val engine = container.tunnelEngineRegistry.require(TunnelEngineKind.XRAY)
            val prepared = engine.prepare(
                owner = coreOwner,
                request = request,
                environment = TunnelEngineEnvironment(
                    network = null,
                    protector = TunnelFileDescriptorProtector { false },
                    dnsServer = null,
                    unboundSocketProtector = { false },
                ),
            )
            val acceptedPrepared = lifecycleGate.withLock {
                if (destroyed.get()) {
                    false
                } else {
                    currentEngineRequest = request
                    currentPreparedSession = prepared
                    currentEngine = engine
                    true
                }
            }
            if (!acceptedPrepared) {
                runCatching { engine.stop(coreOwner, prepared) }
                return false
            }
            val tun = establishTun(
                getString(R.string.vpn_kill_switch_session),
                prepared.tunPlan,
            )
            val acceptedTun = lifecycleGate.withLock {
                if (destroyed.get()) {
                    false
                } else {
                    tunInterface = tun
                    true
                }
            }
            if (!acceptedTun) {
                runCatching { tun.close() }
                return false
            }
            coreLease = engine.start(
                owner = coreOwner,
                prepared = prepared,
                tun = AndroidTunnelFileDescriptorHandle(tun),
            )
            coreRunning = true
            lockdownActive = true
            networkMonitor.stop()
            VpnStateStore.set(
                coreOwner,
                VpnSnapshot(
                    state = VpnConnectionState.LOCKDOWN,
                    failureDetail = failureDetail,
                ),
            )
            showForeground(VpnConnectionState.LOCKDOWN, null)
            acquireWakeLock()
            AppLogger.w(LOG_TAG, "Kill Switch lockdown engaged, non-local traffic is blocked")
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            AppLogger.e(LOG_TAG, "Failed to engage Kill Switch lockdown", error)
            lockdownActive = false
            stopCoreAndTun()
            false
        }
    }

    private fun startAutoHealing() {
        autoHealingJob?.cancel()
        // Ordinary service routes own their exit health without restarting unrelated routes.
        if (serviceRouteConfig != null) return
        if (!container.settings.autoHealingEnabled.value) return
        // The probes run outside a Levik Game VPN (it holds only the games); the sidecar watches its paths.
        if (currentServer?.engine == TunnelEngineKind.LEVIK_GAME) return
        autoHealingJob = serviceScope.launch {
            var failedRounds = 0
            while (coreRunning) {
                delay(if (currentServer?.engine == TunnelEngineKind.LEVIK_DNSTT) {
                    maxOf(60_000L, autoHealingDelayMs(failedRounds))
                } else autoHealingDelayMs(failedRounds))
                if (!coreRunning) break
                val probe = checkConnectivity()
                val nowMs = SystemClock.elapsedRealtime()
                val decision = tunnelHealthPolicy.evaluateProbe(probe.isAlive, nowMs)
                failedRounds = if (decision.assessment == TunnelHealthAssessment.DEGRADED) decision.consecutiveFailures else 0
                when {
                    probe.isAlive -> telemetry.record { it.probeSucceeded() }
                    // Failures right after a network change are expected and not counted.
                    decision.assessment != TunnelHealthAssessment.GRACE_PERIOD -> telemetry.record {
                        it.probeFailed(probeTelemetryCodes(probe.observations.map(TunnelProbeObservation::failure)))
                    }
                }
                when (decision.assessment) {
                    TunnelHealthAssessment.HEALTHY -> finalizePendingAutoFallback()
                    TunnelHealthAssessment.GRACE_PERIOD -> {
                        AppLogger.d(LOG_TAG, "Ignoring tunnel health failure during network grace period")
                    }
                    TunnelHealthAssessment.DEGRADED -> {
                        wakeAllowlistProbe()
                        AppLogger.w(
                            LOG_TAG,
                            "Auto-healing health check failed " +
                                "(${decision.consecutiveFailures}/$AUTO_HEALING_FAILURE_ROUNDS): " +
                                probe.failureSummary(),
                        )
                    }
                    TunnelHealthAssessment.UNHEALTHY -> {
                        wakeAllowlistProbe()
                        AppLogger.w(
                            LOG_TAG,
                            "Tunnel health checks failed across all endpoints: ${probe.failureSummary()}",
                        )
                        if (rollbackPendingAutoFallback(nowMs)) continue
                        if (!tunnelHealthPolicy.canStartRecovery(nowMs)) {
                            AppLogger.w(LOG_TAG, "Auto-healing recovery suppressed by cooldown")
                            continue
                        }
                        val fallbackSuccess = tryAutoFallback(nowMs)
                        if (!fallbackSuccess) {
                            tunnelHealthPolicy.recordRecovery(nowMs)
                            AppLogger.w(LOG_TAG, "No validated fallback available; reconnecting current server")
                            telemetry.record { it.recovery(RecoveryAction.RECONNECT_SAME) }
                            scheduleReconnect(AttemptCause.RECONNECT)
                        }
                    }
                }
            }
        }
    }

    /**
     * Smart Auto, docs/app-platform.md "Smart Auto": a new automatic connection
     * on a regular server must carry real traffic. If it does not while the
     * phone's own network works, the protocol is noted as failing on this
     * network and the next rung of the ladder is tried.
     */
    private fun startTrafficCheck(server: TunnelServer, network: Network) {
        trafficCheckJob?.cancel()
        trafficCheckJob = null
        freezeDetector.reset()
        if (serviceRouteConfig != null) return
        launchTrafficCheck(server, network, TRAFFIC_CHECK_DELAY_MS)
    }

    /** The stats saw a tunnel that sends but hardly receives: check it the same way. */
    private fun onFreezeSuspected() {
        if (serviceRouteConfig != null) return
        if (trafficCheckJob?.isActive == true) return
        val server = currentServer ?: return
        val network = currentNetwork ?: return
        AppLogger.w(LOG_TAG, "Smart Auto: the tunnel sends but hardly receives; checking traffic")
        wakeAllowlistProbe()
        launchTrafficCheck(server, network, initialDelayMs = 0L)
    }

    private fun launchTrafficCheck(server: TunnelServer, network: Network, initialDelayMs: Long) {
        val allowlist = server.isMobileServer() && allowlistSessionActive()
        if (!allowlist && (!container.settings.automaticServer.value || !server.isSmartAutoCandidate())) return
        // Anti-Block sends unlisted sites around the tunnel: the check would pass without it.
        if (container.settings.routingPreset.value == RoutingPreset.BLOCKED_ONLY) return
        val smartAuto = container.appPlatform.smartAuto
        trafficCheckJob = serviceScope.launch {
            delay(initialDelayMs)
            val vpnNetwork = vpnNetwork() ?: return@launch
            val size = if (allowlist) TrafficCheckSize.ALLOWLIST else TrafficCheckSize.REGULAR
            val elapsedMs = smartAuto.checkTraffic(vpnNetwork, size)
            val passed = elapsedMs != null
            if (!coreRunning || currentServer?.id != server.id || currentNetwork != network) return@launch
            val checkedNode = FavoriteNodes.nodeId(server)
            if (!allowlist && elapsedMs != null && checkedNode != null) {
                telemetry.record { it.trafficCheck(checkedNode, elapsedMs, ok = true) }
            }
            if (allowlist) {
                onAllowlistTrafficChecked(server, network, passed)
                return@launch
            }
            if (passed) {
                allowlistOffSource = null
                freezeDetector.checkPassed()
                smartAuto.recordSuccess(server, network)
                smartLadder.reset()
                tunnelHealthPolicy.markCandidateHealthy(server.id)
                AppLogger.i(LOG_TAG, "Smart Auto: traffic passes on ${server.smartProtocol()}")
                return@launch
            }
            if (!smartAuto.physicalWorks(network)) {
                AppLogger.w(LOG_TAG, "Smart Auto: traffic check failed while the network itself is offline; ignored")
                return@launch
            }
            // A failure counts as the check's deadline, docs/app-platform.md "Node quality".
            if (checkedNode != null) telemetry.record { it.trafficCheck(checkedNode, TrafficCheckSize.REGULAR.deadlineMs, ok = false) }
            if (returnAfterFalseAllowlistEnd(server, network)) return@launch
            if (networkMonitor.isCellular(network) && allowlistsActive(network)) {
                // Allowlists, not the protocol: every regular server fails here, a mobile one is next.
                AppLogger.w(LOG_TAG, "Smart Auto: no traffic under allowlists; probing to move to a mobile server")
                wakeAllowlistProbe()
                return@launch
            }
            smartAuto.recordFailure(server, network)
            tunnelHealthPolicy.markCandidateFailed(server.id, SystemClock.elapsedRealtime())
            climbProtocolLadder(server, network)
        }
    }

    private suspend fun allowlistsActive(network: Network): Boolean =
        allowlistHeld(network) || try {
            container.whitelistDetector.detect(network, forceRefresh = true) == WhitelistMode.ACTIVE
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }

    private suspend fun onAllowlistTrafficChecked(server: TunnelServer, network: Network, passed: Boolean) {
        val smartAuto = container.appPlatform.smartAuto
        if (passed) {
            freezeDetector.checkPassed()
            if (lastWhitelistMode == WhitelistMode.ACTIVE) smartAuto.recordAllowlistSuccess(server, network)
            tunnelHealthPolicy.markCandidateHealthy(server.id)
            AppLogger.i(LOG_TAG, "Allowlists: traffic passes on ${server.smartProtocol()}")
            return
        }
        // Only with allowlists on does a failure say something about this server under them.
        val mode = try {
            container.whitelistDetector.detect(network, forceRefresh = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            WhitelistMode.UNKNOWN
        }
        if (mode != WhitelistMode.ACTIVE) {
            AppLogger.w(LOG_TAG, "Allowlists: traffic check failed while allowlists are not on; ignored")
            return
        }
        lastWhitelistMode = mode
        smartAuto.recordAllowlistFailure(server, network)
        tunnelHealthPolicy.markCandidateFailed(server.id, SystemClock.elapsedRealtime())
        advanceAllowlist(server, network, requireRunning = true) ?: return
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopConnection(stopService = false)
            connect()
        }
    }

    private suspend fun climbProtocolLadder(failed: TunnelServer, network: Network) {
        val smartAuto = container.appPlatform.smartAuto
        smartLadder.tried(failed.id, failed.smartProtocol())
        if (smartLadder.exhausted()) {
            AppLogger.w(LOG_TAG, "Smart Auto: no rung of the protocol ladder carried traffic; staying")
            return
        }
        val profile = runCatching { readPreparedProfile() }.getOrNull() ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val pool = profile.servers.filter { server ->
            server.id != failed.id &&
                server.isSmartAutoCandidate() &&
                !container.appPlatform.underMaintenance(server) &&
                tunnelHealthPolicy.isCandidateEligible(server.id, nowMs)
        }
        if (pool.isEmpty()) return
        val ranked = smartAuto.rank(pool, measureLatencies(pool), network)
        val next = SmartServerRanking.nextRung(ranked, smartLadder.protocols(), smartLadder.ids()) ?: return
        val accepted = connectionMutex.withLock {
            coreRunning && currentServer?.id == failed.id && container.settings.automaticServer.value
        }
        if (!accepted) return
        smartLadder.climbed()
        pendingAutoFallback = null
        container.secureStore.put(SecureFileStore.SELECTED_SERVER, next.id.encodeToByteArray())
        smartAuto.reportSwitch(
            SmartSwitch(
                failed.id,
                failed.smartProtocol(),
                next.id,
                next.protocol,
                if (next.protocol != failed.smartProtocol()) SmartSwitchReason.PROTOCOL_FAILED else SmartSwitchReason.SERVER_FAILED,
            ),
        )
        AppLogger.i(LOG_TAG, "Smart Auto: ${failed.smartProtocol()} carries no traffic here; trying ${next.protocol}")
        telemetry.record { it.recovery(RecoveryAction.FAILOVER) }
        nextAttemptCause = AttemptCause.FAILOVER
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopConnection(stopService = false)
            connect()
        }
    }

    /** The fastest working ordinary Xray server for Levik Game's other traffic; null when there is none. */
    private suspend fun gameCarrier(profile: PreparedTunnelProfile, network: Network?): TunnelServer? {
        val pool = profile.servers.filter {
            it.engine == TunnelEngineKind.XRAY && it.isSmartAutoCandidate() && !container.appPlatform.underMaintenance(it) &&
                // The Game configuration holds this one outbound: it cannot dial through another.
                "dialerProxy" !in it.outbound.toString()
        }
        if (pool.isEmpty()) {
            AppLogger.w(LOG_TAG, "Levik Game: the profile has no ordinary Xray server for the other game traffic")
            return null
        }
        val measured = measureLatencies(pool)
        val ranked = container.appPlatform.smartAuto.rank(pool, measured, network)
            .mapNotNull { item -> pool.firstOrNull { it.id == item.id } }
        // A ping that does not come back is not proof of a dead server: then the user's own
        // last ordinary server, else Smart Auto's best by history.
        val last = readSecureString(SecureFileStore.LAST_REGULAR_SERVER)?.let { id -> pool.firstOrNull { it.id == id } }
        AppLogger.i(LOG_TAG, "Levik Game: ${pool.size} ordinary servers, ${measured.values.count { it != null }} answered")
        return ranked.firstOrNull { measured[it.id] != null } ?: last ?: ranked.firstOrNull() ?: pool.first()
    }

    private suspend fun measureLatencies(servers: List<TunnelServer>): Map<String, Long?> = supervisorScope {
        servers.map { server ->
            async(Dispatchers.IO) { server.id to runCatching { ServerPinger.measure(server) }.getOrNull() }
        }.awaitAll().toMap()
    }

    @Suppress("DEPRECATION") // Snapshot all visible networks, as checkConnectivity does.
    private fun vpnNetwork(): Network? {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** [rollbackTo]: the server to return to should this fallback fail too, by default the current one. */
    private suspend fun tryAutoFallback(nowMs: Long, rollbackTo: String? = null): Boolean {
        if (!container.settings.autoFallbackServer.value) return false
        if (currentServer?.isMobileServer() == true) return false
        // Levik Game is a choice, never replaced by an ordinary server.
        if (currentServer?.engine == TunnelEngineKind.LEVIK_GAME) return false
        val profile = runCatching { readPreparedProfile() }.getOrNull() ?: return false
        if (profile.servers.size <= 1) return false

        val currentId = currentServer?.id ?: readSelectedServerId()
            ?: profile.servers.firstOrNull()?.id
            ?: return false
        val candidates = profile.servers.filter { server ->
            server.id != currentId &&
                server.isEligibleForAutomaticSelection() &&
                !server.isMobileServer() &&
                !container.appPlatform.underMaintenance(server) &&
                tunnelHealthPolicy.isCandidateEligible(server.id, nowMs)
        }
        if (candidates.isEmpty()) return false

        AppLogger.i(LOG_TAG, "Current server seems stalled, testing ${candidates.size} fallback servers")
        val measured = measureLatencies(candidates)
        // Smart Auto's order (load, this network's protocol history), among servers that may be up.
        val targetServer = container.appPlatform.smartAuto.rank(candidates, measured, currentNetwork)
            .firstNotNullOfOrNull { ranked ->
                candidates.firstOrNull { it.id == ranked.id }
                    ?.takeIf { measured[it.id] != null || !ServerPinger.silenceMeansDown(it) }
            }
        if (targetServer == null) {
            AppLogger.w(LOG_TAG, "No fallback server passed the underlying-network reachability check")
            return false
        }

        pendingAutoFallback = PendingAutoFallback(
            sourceServerId = rollbackTo ?: currentId,
            targetServerId = targetServer.id,
        )
        tunnelHealthPolicy.markCandidateFailed(currentId, nowMs)
        tunnelHealthPolicy.recordRecovery(nowMs)
        AppLogger.i(LOG_TAG, "Auto-failover selected a reachable candidate; tunnel validation pending")
        telemetry.record { it.recovery(RecoveryAction.FAILOVER) }

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopConnection(stopService = false)
            connect()
        }
        return true
    }

    private fun finalizePendingAutoFallback() {
        val pending = pendingAutoFallback ?: return
        if (currentServer?.id != pending.targetServerId) return
        runCatching {
            container.secureStore.put(
                SecureFileStore.SELECTED_SERVER,
                pending.targetServerId.encodeToByteArray(),
            )
        }.onSuccess {
            pendingAutoFallback = null
            tunnelHealthPolicy.markCandidateHealthy(pending.targetServerId)
            AppLogger.i(LOG_TAG, "Auto-failover tunnel validated; server selection committed")
        }.onFailure { error ->
            AppLogger.e(LOG_TAG, "Failed to persist validated auto-failover server", error)
        }
    }

    private suspend fun rollbackPendingAutoFallback(nowMs: Long): Boolean {
        val pending = pendingAutoFallback ?: return false
        if (currentServer?.id != pending.targetServerId) {
            pendingAutoFallback = null
            return false
        }
        pendingAutoFallback = null
        // The source failed a moment ago: going straight back to it kept phones on a dead
        // server for minutes. Another candidate first; the source only when none is left.
        if (tryAutoFallback(nowMs, rollbackTo = pending.sourceServerId)) return true
        tunnelHealthPolicy.markCandidateFailed(pending.targetServerId, nowMs)
        tunnelHealthPolicy.recordRecovery(nowMs)
        runCatching {
            container.secureStore.put(
                SecureFileStore.SELECTED_SERVER,
                pending.sourceServerId.encodeToByteArray(),
            )
        }.onFailure { error ->
            AppLogger.e(LOG_TAG, "Failed to restore server after auto-failover validation failure", error)
        }
        AppLogger.w(LOG_TAG, "Auto-failover candidate failed tunnel validation; rolling back")
        telemetry.record { it.recovery(RecoveryAction.ROLLBACK) }
        nextAttemptCause = AttemptCause.ROLLBACK
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopConnection(stopService = false)
            connect()
        }
        return true
    }

    private suspend fun pauseConnection(minutes: Int, home: String? = null) = connectionMutex.withLock {
        telemetry.record { it.pause() }
        pendingAutoFallback = null
        tunnelHealthPolicy.reset()
        pauseJob?.cancel()
        connectionJob?.cancel()
        reconnectJob?.cancel()
        statsJob?.cancel()
        profileExpiryJob?.cancel()
        autoHealingJob?.cancel()
        yandexRefreshJob?.cancel()
        yandexRefreshJob = null
        relayEntitlementWatchdogJob?.cancel()
        relayEntitlementWatchdogJob = null
        trafficCheckJob?.cancel()
        trafficCheckJob = null
        lockdownActive = false
        networkMonitor.stop(releaseCellular = false)
        val serverNameBeforePause = currentServerName
        val serverBeforePause = currentServer
        stopCoreAndTun()

        val pauseDurationMs = minutes * 60_000L
        val pauseEndsAt = System.currentTimeMillis() + pauseDurationMs
        container.settings.setPausedUntilMs(pauseEndsAt)

        val initialRemaining = (pauseDurationMs / 1000L)
        VpnStateStore.set(
            coreOwner,
            VpnSnapshot(
                state = VpnConnectionState.PAUSED,
                engine = serverBeforePause?.engine,
                serverId = serverBeforePause?.id,
                serverName = serverNameBeforePause,
                serverCountryCode = serverBeforePause?.countryCode,
                effectiveRoutingProfile = serverBeforePause?.effectiveRoutingProfile(container.settings.routingPreset.value)
                    ?: EffectiveRoutingProfile.USER_SELECTED,
                pausedRemainingSeconds = initialRemaining,
                pausedAtHome = home,
            ),
        )
        showForeground(
            VpnConnectionState.PAUSED,
            serverNameBeforePause,
            initialRemaining,
            serverBeforePause?.countryCode,
            home,
        )

        pauseJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                val remaining = ((pauseEndsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                if (remaining <= 0) {
                    AppLogger.i(LOG_TAG, "VPN pause expired, automatically resuming connection")
                    container.settings.setPausedUntilMs(0L)
                    pauseJob = null
                    telemetry.record { it.unpause() }
                    nextAttemptCause = AttemptCause.RESUME
                    connectionJob?.cancel()
                    connectionJob = serviceScope.launch {
                        connect()
                    }
                    break
                }
                VpnStateStore.update(coreOwner) {
                    it.copy(
                        state = VpnConnectionState.PAUSED,
                        serverName = serverNameBeforePause,
                        pausedRemainingSeconds = remaining,
                    )
                }
                showForeground(
                    VpnConnectionState.PAUSED,
                    serverNameBeforePause,
                    remaining,
                    serverBeforePause?.countryCode,
                    home,
                )
            }
        }
    }

    private suspend fun resumeConnection() {
        if (pauseJob != null) telemetry.record { it.unpause() }
        nextAttemptCause = AttemptCause.RESUME
        pauseJob?.cancel()
        pauseJob = null
        container.settings.setPausedUntilMs(0L)
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            connect()
        }
    }

    // During network transitions the VPN may not yet be the default network.
    @Suppress("DEPRECATION") // Snapshot all visible networks to avoid probing physical egress.
    private suspend fun checkConnectivity(): TunnelProbeResult = withContext(Dispatchers.IO) {
        val manager = getSystemService(ConnectivityManager::class.java)
        val vpnNetwork = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: return@withContext TunnelProbeResult(listOf(
            TunnelProbeObservation("vpn", success = false, failure = "network_unavailable"),
        ))
        val observations = mutableListOf<TunnelProbeObservation>()
        val dnsTunnel = currentServer?.engine == TunnelEngineKind.LEVIK_DNSTT
        val relay = dnsTunnel || currentServer?.engine in DOCUMENT_RELAY_ENGINES
        // Probe the IP endpoint first on relay, avoiding a DNS lookup on the
        // common path while still retaining independent endpoint fallbacks.
        val endpoints = if (relay) HEALTH_CHECK_ENDPOINTS else HEALTH_CHECK_ENDPOINTS.shuffled()
        val timeoutMs = if (dnsTunnel) 20_000 else if (relay) RELAY_HEALTH_CHECK_TIMEOUT_MS else HEALTH_CHECK_TIMEOUT_MS
        for (endpoint in endpoints) {
            coroutineContext.ensureActive()
            val observation = probeEndpoint(vpnNetwork, endpoint, timeoutMs)
            coroutineContext.ensureActive()
            observations += observation
            if (observation.success) break
        }
        TunnelProbeResult(observations)
    }

    private fun probeEndpoint(
        network: Network,
        endpoint: HealthCheckEndpoint,
        timeoutMs: Int,
    ): TunnelProbeObservation {
        var connection: HttpURLConnection? = null
        return try {
            connection = (network.openConnection(URL(endpoint.url)) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }
            val status = connection.responseCode
            if (status in 100..599) {
                TunnelProbeObservation(endpoint.name, success = true)
            } else {
                TunnelProbeObservation(endpoint.name, success = false, failure = "http_$status")
            }
        } catch (error: Exception) {
            TunnelProbeObservation(
                endpoint = endpoint.name,
                success = false,
                failure = probeFailureCode(error),
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun sessionTrigger(intent: Intent?): SessionTrigger = when {
        // START_STICKY restarts after the process was killed carry no intent.
        intent == null -> SessionTrigger.UNKNOWN
        intent.action == VpnService.SERVICE_INTERFACE -> SessionTrigger.ALWAYS_ON
        else -> SessionTrigger.fromWire(intent.getStringExtra(EXTRA_TRIGGER))
    }

    /** Starts a telemetry session, or labels the next attempt of the running one. */
    private fun beginOrContinueTelemetry(trigger: SessionTrigger, continuedCause: AttemptCause) {
        if (telemetry.active) {
            nextAttemptCause = continuedCause
            return
        }
        val settings = container.settings
        telemetry.begin(
            trigger,
            TelemetrySessionSettings(
                killSwitch = settings.killSwitchEnabled.value,
                autoRecovery = settings.autoHealingEnabled.value,
                splitTunnel = settings.splitTunnelMode.value != SplitTunnelMode.OFF ||
                    settings.routingPreset.value != RoutingPreset.GLOBAL,
                batteryUnrestricted = isBatteryUnrestricted(this),
            ),
        )
        nextAttemptCause = AttemptCause.INITIAL
    }

    private fun prepareTelemetryNetwork(network: Network) {
        if (!telemetry.active) return
        telemetry.prepareNetwork(network.toString(), networkType(network)) {
            container.telemetryTransport.networkToken(network)
        }
    }

    private fun networkType(network: Network): NetworkType =
        telemetryNetworkType(getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network))

    /** Relay-type engines negotiate with the server while starting; Xray starts locally. */
    private fun startStage(server: TunnelServer): AttemptStage =
        if (server.engine == TunnelEngineKind.XRAY) AttemptStage.CORE else AttemptStage.HANDSHAKE

    /** The tunnel was given up: Kill Switch holds traffic or the service stops. */
    private fun endTelemetryAfterFailure(enteredLockdown: Boolean, code: String) {
        if (enteredLockdown) telemetry.record { it.recovery(RecoveryAction.LOCKDOWN) }
        telemetry.finish(EndBy.ERROR, code)
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            val uid = Process.myUid()
            val startedAt = SystemClock.elapsedRealtime()
            val initialRx = supportedTrafficValue(TrafficStats.getUidRxBytes(uid))
            val initialTx = supportedTrafficValue(TrafficStats.getUidTxBytes(uid))
            var lastRx = initialRx
            var lastTx = initialTx
            var lastSampleAt = startedAt

            while (true) {
                delay(STATS_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val rx = supportedTrafficValue(TrafficStats.getUidRxBytes(uid))
                val tx = supportedTrafficValue(TrafficStats.getUidTxBytes(uid))
                val elapsedMs = (now - lastSampleAt).coerceAtLeast(1)
                val rxRate = ((rx - lastRx).coerceAtLeast(0) * 1000L) / elapsedMs
                val txRate = ((tx - lastTx).coerceAtLeast(0) * 1000L) / elapsedMs
                val rxDelta = (rx - lastRx).coerceAtLeast(0)
                val txDelta = (tx - lastTx).coerceAtLeast(0)
                if (rxDelta > 0 || txDelta > 0) {
                    container.trafficHistoryStore.recordTraffic(rxDelta, txDelta)
                }
                if (freezeDetector.record(rx, tx, now)) onFreezeSuspected()
                VpnStateStore.update(coreOwner) { state ->
                    state.copy(
                        downloadedBytes = (rx - initialRx).coerceAtLeast(0),
                        uploadedBytes = (tx - initialTx).coerceAtLeast(0),
                        downloadBytesPerSecond = rxRate,
                        uploadBytesPerSecond = txRate,
                        connectedDurationSeconds = (now - startedAt) / 1000L,
                    )
                }
                lastRx = rx
                lastTx = tx
                lastSampleAt = now
            }
        }
    }

    private fun supportedTrafficValue(value: Long): Long =
        if (value == TrafficStats.UNSUPPORTED.toLong()) 0 else value.coerceAtLeast(0)

    private fun readPreparedProfile(): PreparedTunnelProfile {
        val bytes = container.secureStore.get(SecureFileStore.TUNNEL_PROFILE)
            ?: error("Encrypted tunnel profile is unavailable")
        return try {
            container.json.decodeFromString<PreparedTunnelProfile>(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
    }

    private fun readSelectedServerId(): String? {
        val bytes = container.secureStore.get(SecureFileStore.SELECTED_SERVER) ?: return null
        return try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun checkDisclosureConsent() {
        val bytes = container.secureStore.get(SecureFileStore.VPN_DISCLOSURE_CONSENT)
            ?: error("VPN disclosure consent is missing")
        try {
            check(bytes.contentEquals(CONSENT_VALUE))
        } finally {
            bytes.fill(0)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.vpn_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showForeground(
        state: VpnConnectionState,
        serverName: String?,
        remainingSeconds: Long = 0,
        serverCountryCode: String? = currentServer?.countryCode,
        home: String? = null,
    ) {
        val notification = buildNotification(
            state,
            serverName,
            remainingSeconds,
            serverCountryCode,
            home,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        state: VpnConnectionState,
        serverName: String?,
        remainingSeconds: Long = 0,
        serverCountryCode: String? = currentServer?.countryCode,
        home: String? = null,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            Intent(this, LevikVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resumeIntent = PendingIntent.getService(
            this,
            REQUEST_RESUME,
            Intent(this, LevikVpnService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            REQUEST_PAUSE,
            Intent(this, LevikVpnService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_PAUSE_MINUTES, DEFAULT_PAUSE_MINUTES),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val formattedTime = String.format(
            java.util.Locale.US,
            "%02d:%02d",
            remainingSeconds / 60,
            remainingSeconds % 60,
        )

        val text = when (state) {
            VpnConnectionState.CONNECTED -> getString(
                R.string.vpn_notification_connected,
                serverName.orEmpty(),
                countryDisplay(serverCountryCode),
            )
            VpnConnectionState.PAUSED -> if (home != null) {
                getString(R.string.vpn_notification_home, home)
            } else {
                getString(R.string.vpn_notification_paused, formattedTime)
            }
            VpnConnectionState.RECONNECTING -> getString(
                R.string.vpn_notification_reconnecting,
            )
            VpnConnectionState.CONNECTING -> getString(R.string.vpn_notification_connecting)
            VpnConnectionState.LOCKDOWN -> getString(R.string.vpn_notification_lockdown)
            else -> getString(R.string.vpn_notification_idle)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(this, container.settings.appIcon.value))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (state == VpnConnectionState.PAUSED) {
            builder.addAction(
                R.drawable.ic_power,
                getString(R.string.vpn_notification_resume),
                resumeIntent,
            )
        }
        if (state == VpnConnectionState.CONNECTED) {
            builder.addAction(
                R.drawable.ic_refresh,
                getString(R.string.vpn_notification_pause),
                pauseIntent,
            )
        }
        builder.addAction(
            R.drawable.ic_power,
            getString(R.string.vpn_notification_disconnect),
            disconnectIntent,
        )

        return builder.build()
    }

    private data class PendingAutoFallback(
        val sourceServerId: String,
        val targetServerId: String,
    )

    private data class HealthCheckEndpoint(
        val name: String,
        val url: String,
    )

    private data class TunnelProbeObservation(
        val endpoint: String,
        val success: Boolean,
        val failure: String? = null,
    )

    private data class TunnelProbeResult(
        val observations: List<TunnelProbeObservation>,
    ) {
        val isAlive: Boolean = observations.any(TunnelProbeObservation::success)

        fun failureSummary(): String = observations
            .asSequence()
            .filterNot(TunnelProbeObservation::success)
            .joinToString(separator = ", ") { observation ->
                "${observation.endpoint}:${observation.failure ?: "failed"}"
            }
            .take(MAX_HEALTH_FAILURE_SUMMARY_LENGTH)
            .ifBlank { "no endpoint returned a valid response" }
    }

    companion object {
        const val ACTION_CONNECT = "com.leviknet.vpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.leviknet.vpn.action.DISCONNECT"
        const val ACTION_RECONNECT = "com.leviknet.vpn.action.RECONNECT"
        const val ACTION_RECONFIGURE = "com.leviknet.vpn.action.RECONFIGURE"
        const val ACTION_SWITCH_SERVER = "com.leviknet.vpn.action.SWITCH_SERVER"
        const val ACTION_PAUSE = "com.leviknet.vpn.action.PAUSE"
        const val ACTION_RESUME = "com.leviknet.vpn.action.RESUME"
        const val EXTRA_SERVER_ID = "com.leviknet.vpn.extra.SERVER_ID"
        const val EXTRA_PAUSE_MINUTES = "com.leviknet.vpn.extra.PAUSE_MINUTES"
        const val EXTRA_PAUSE_HOME = "com.leviknet.vpn.extra.PAUSE_HOME"

        /** Levik Home's pause ends when the phone leaves; this only bounds it. */
        const val HOME_PAUSE_MINUTES = 24 * 60
        private const val MAX_HOME_NAME = 60
        const val EXTRA_TRIGGER = "com.leviknet.vpn.extra.TRIGGER"
        const val EXTRA_END_BY = "com.leviknet.vpn.extra.END_BY"
        const val EXTRA_END_CODE = "com.leviknet.vpn.extra.END_CODE"

        private const val NOTIFICATION_CHANNEL_ID = "levik_vpn_connection"
        private const val LOG_TAG = "LevikVpnService"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_OPEN_APP = 4102
        private const val REQUEST_DISCONNECT = 4103
        private const val REQUEST_RESUME = 4104
        private const val REQUEST_PAUSE = 4105
        private const val DEFAULT_PAUSE_MINUTES = 15
        private const val RECONNECT_DEBOUNCE_MS = 750L
        private const val MOBILE_NETWORK_DEBOUNCE_MS = 1_500L
        private const val MOBILE_AUTOMATION_INITIAL_DELAY_MS = 8_000L
        private const val MOBILE_IDLE_INTERVAL_MS = 5 * 60_000L
        private const val MOBILE_CONFIRM_INTERVAL_MS = 6_000L
        private const val ALLOWLIST_HOLD_MS = 30 * 60_000L
        private const val ALLOWLIST_MAX_CLIMBS = 4
        private const val AUTOMATIC_SWITCH_ROLLBACK_DELAY_MS = 1_000L
        private const val TRAFFIC_CHECK_DELAY_MS = 1_500L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val AUTO_HEALING_FAILURE_ROUNDS = 3
        private const val AUTO_HEALING_NETWORK_GRACE_MS = 20_000L
        private const val AUTO_HEALING_RECOVERY_COOLDOWN_MS = 5 * 60_000L
        private const val AUTO_HEALING_CANDIDATE_BACKOFF_MS = 15 * 60_000L
        private const val HEALTH_CHECK_TIMEOUT_MS = 4_000
        private const val RELAY_HEALTH_CHECK_TIMEOUT_MS = 12_000
        private const val MAX_HEALTH_FAILURE_SUMMARY_LENGTH = 220
        private const val RELAY_ENTITLEMENT_WATCHDOG_INTERVAL_MS = 120_000L
        private const val WAKELOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private const val MAX_FAILURE_DETAIL_LENGTH = 300
        private val HEALTH_CHECK_ENDPOINTS = listOf(
            HealthCheckEndpoint("cloudflare", "https://1.1.1.1/cdn-cgi/trace"),
            HealthCheckEndpoint("google", "https://www.gstatic.com/generate_204"),
            HealthCheckEndpoint("apple", "https://captive.apple.com/hotspot-detect.html"),
        )
        private val CONSENT_VALUE = "accepted-v1".encodeToByteArray()
        private val NEXT_CORE_OWNER = AtomicLong(0)
    }
}

private data class NativeCleanup(
    val engine: TunnelEngineAdapter?,
    val prepared: PreparedTunnelEngineSession?,
    val lease: Long?,
    val tunInterface: ParcelFileDescriptor?,
)

private class NetworkSetupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private val DOCUMENT_RELAY_ENGINES = setOf(TunnelEngineKind.LEVIK_RELAY, TunnelEngineKind.LEVIK_YANDEX)
