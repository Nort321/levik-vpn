package com.leviknet.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.security.SecureFileStore
import com.leviknet.vpn.data.DnsProvider
import com.leviknet.vpn.data.SplitTunnelMode
import com.leviknet.vpn.data.isActiveAt
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LevikVpnService : VpnService() {
    private val coreOwner = NEXT_CORE_OWNER.incrementAndGet()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
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
    private var currentEngine: TunnelEngineAdapter? = null
    private var currentServer: TunnelServer? = null
    private var currentSubscriptionId: String? = null
    @Volatile
    private var currentServerName: String? = null
    private var currentNetwork: Network? = null
    @Volatile
    private var coreRunning = false
    @Volatile
    private var lockdownActive = false
    private var coreLease: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectJob: Job? = null
    private var statsJob: Job? = null
    private var profileExpiryJob: Job? = null
    private var autoHealingJob: Job? = null
    private var relayEntitlementWatchdogJob: Job? = null
    private var connectionJob: Job? = null
    private var pauseJob: Job? = null

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

    private fun fileDescriptorProtector(network: Network) =
        TunnelFileDescriptorProtector protector@{ fd ->
            if (fd !in 0..Int.MAX_VALUE.toLong()) return@protector false
            val socketFd = fd.toInt()
            if (!protect(socketFd)) return@protector false
            runCatching {
                // The descriptor received over SCM_RIGHTS already has an Android owner.
                // fromFd duplicates it; adoptFd would claim the same descriptor again and
                // Android fdsan aborts the whole process for that ownership violation.
                ParcelFileDescriptor.fromFd(socketFd).use { pfd ->
                    network.bindSocket(pfd.fileDescriptor)
                }
                true
            }.getOrDefault(false)
        }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(LOG_TAG, "LevikVpnService onCreate")
        container.tunnelEngineRegistry.claimOwner(coreOwner)
        VpnStateStore.claim(coreOwner)
        ServerPinger.registerSocketProtector(coreOwner, ::protectPingSocket, ::protectPingDatagramSocket)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECT
        AppLogger.d(LOG_TAG, "onStartCommand action: $action")
        when (action) {
            ACTION_DISCONNECT -> {
                pauseJob?.cancel()
                pauseJob = null
                connectionJob?.cancel()
                connectionJob = null
                container.settings.setPausedUntilMs(0L)
                serviceScope.launch {
                    stopConnection(stopService = true)
                }
            }
            ACTION_PAUSE -> {
                val minutes = intent?.getIntExtra(EXTRA_PAUSE_MINUTES, 15) ?: 15
                serviceScope.launch {
                    pauseConnection(minutes)
                }
            }
            ACTION_RESUME -> {
                serviceScope.launch {
                    resumeConnection()
                }
            }
            ACTION_RECONNECT -> scheduleReconnect()
            ACTION_RECONFIGURE, ACTION_SWITCH_SERVER -> {
                pauseJob?.cancel()
                pauseJob = null
                container.settings.setPausedUntilMs(0L)
                val newServerId = intent?.getStringExtra(EXTRA_SERVER_ID)
                if (newServerId != null) {
                    container.secureStore.put(SecureFileStore.SELECTED_SERVER, newServerId.encodeToByteArray())
                }
                connectionJob?.cancel()
                connectionJob = serviceScope.launch {
                    stopConnection(stopService = false)
                    connect()
                }
            }
            else -> {
                pauseJob?.cancel()
                pauseJob = null
                container.settings.setPausedUntilMs(0L)
                if (coreRunning && !lockdownActive) {
                    VpnStateStore.update(coreOwner) {
                        it.copy(
                            state = VpnConnectionState.CONNECTED,
                            engine = currentServer?.engine,
                            serverId = currentServer?.id,
                            serverName = currentServerName,
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
                showForeground(VpnConnectionState.CONNECTING, null)
                connectionJob = serviceScope.launch {
                    connect()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        AppLogger.w(LOG_TAG, "VPN permission revoked by system/user")
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
        AppLogger.i(LOG_TAG, "LevikVpnService onDestroy")
        ServerPinger.unregisterSocketProtector(coreOwner)
        runCatching { container.trafficHistoryStore.flushAsync() }
        val cleanup = lifecycleGate.withLock {
            destroyed.set(true)
            lockdownActive = false
            pauseJob?.cancel()
            pauseJob = null
            connectionJob?.cancel()
            reconnectJob?.cancel()
            statsJob?.cancel()
            profileExpiryJob?.cancel()
            autoHealingJob?.cancel()
            relayEntitlementWatchdogJob?.cancel()
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
            currentEngine = null
            currentServer = null
            currentSubscriptionId = null
            currentServerName = null
            currentNetwork = null
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

    private suspend fun connect() = connectionMutex.withLock connection@{
        if (destroyed.get()) return@connection
        if (lockdownActive) {
            // A fresh connect attempt always replaces the Kill Switch lockdown TUN.
            lockdownActive = false
            stopCoreAndTun()
        }
        if (coreRunning) return@connection
        VpnStateStore.set(
            coreOwner,
            VpnSnapshot(state = VpnConnectionState.CONNECTING),
        )
        showForeground(VpnConnectionState.CONNECTING, null)

        try {
            checkDisclosureConsent()
            val profile = readPreparedProfile()
            profile.subscriptionExpiresAt?.let { value ->
                require(Instant.parse(value).isAfter(Instant.now())) {
                    "Subscription has expired"
                }
            }
            val selectedId = readSelectedServerId()
            val selected = profile.servers.firstOrNull { it.id == selectedId }
                ?: profile.servers.firstOrNull(TunnelServer::isEligibleForAutomaticSelection)
                ?: error("Tunnel profile has no server eligible for automatic selection")
            val connectionExpiresAt = selected.relayConfig?.bootstrap?.expiresAt
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
            val dnsProvider = container.settings.dnsProvider.value
            val primaryDns = if (dnsProvider == DnsProvider.CUSTOM) {
                container.settings.customDnsIpv4.value.trim().ifBlank { dnsProvider.primaryIpv4 }
            } else {
                dnsProvider.primaryIpv4
            }
            val secondaryDns = if (dnsProvider == DnsProvider.CUSTOM) "8.8.8.8" else dnsProvider.secondaryIpv4

            val routingPreset = container.settings.routingPreset.value
            val antiDpi = container.settings.antiDpiEnabled.value
            val useDoh = container.settings.useDoh.value
            val dohUrl = if (useDoh) {
                if (dnsProvider == DnsProvider.CUSTOM) {
                    container.settings.customDohUrl.value.ifBlank { null }
                } else {
                    dnsProvider.dohUrl
                }
            } else null

            val request = when (selected.engine) {
                TunnelEngineKind.XRAY -> TunnelEngineRequest.Xray(
                    configFactory = XrayConfigFactory { tunFileDescriptor ->
                        XrayConfigBuilder(container.json).build(
                            profile = profile,
                            selectedServerId = selected.id,
                            tunFileDescriptor = tunFileDescriptor,
                            routingPreset = routingPreset,
                            bypassRussianTraffic = container.settings.bypassRussianTraffic.value,
                            russianDirectCidrs = container.russianRoutingData.cidrs,
                            primaryDnsIp = primaryDns,
                            secondaryDnsIp = secondaryDns,
                            dohEndpoint = dohUrl,
                            antiDpiEnabled = antiDpi,
                            antiDpiPackets = container.settings.antiDpiPackets.value,
                            antiDpiLength = container.settings.antiDpiLength.value,
                            antiDpiInterval = container.settings.antiDpiInterval.value,
                            customDirectDomains = container.settings.customDirectDomains.value,
                            customProxyDomains = container.settings.customProxyDomains.value,
                        )
                    },
                    tunPlan = xrayTunPlan(
                        primaryDns = primaryDns,
                        secondaryDns = secondaryDns,
                        primaryDnsIpv6 = dnsProvider.primaryIpv6,
                        secondaryDnsIpv6 = dnsProvider.secondaryIpv6,
                    ),
                )
                TunnelEngineKind.LEVIK_RELAY -> TunnelEngineRequest.Relay(
                    requireNotNull(selected.relayConfig) {
                        "Relay server has no bootstrap configuration"
                    },
                )
            }
            val engine = container.tunnelEngineRegistry.require(selected.engine)
            check(!destroyed.get()) { "VPN service was destroyed during startup" }
            val prepared = engine.prepare(
                owner = coreOwner,
                request = request,
                environment = TunnelEngineEnvironment(
                    network = network,
                    protector = fileDescriptorProtector(network),
                    dnsServer = "$primaryDns:53",
                    terminalFailureHandler = ::onTunnelEngineTerminalFailure,
                ),
            )
            checkConnectionDeadline(connectionExpiryDeadline)
            val acceptedPrepared = lifecycleGate.withLock {
                if (destroyed.get()) {
                    false
                } else {
                    currentEngineRequest = request
                    currentPreparedSession = prepared
                    currentEngine = engine
                    currentServer = selected
                    currentSubscriptionId = profile.subscriptionId
                    currentServerName = selected.name
                    true
                }
            }
            if (!acceptedPrepared) {
                runCatching { engine.stop(coreOwner, prepared) }
                return@connection
            }
            val tun = try {
                establishTun(selected.name, prepared.tunPlan)
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
            coreLease = engine.start(
                owner = coreOwner,
                prepared = prepared,
                tun = AndroidTunnelFileDescriptorHandle(tun),
            )
            checkConnectionDeadline(connectionExpiryDeadline)
            coreRunning = true
            val published = lifecycleGate.withLock {
                if (destroyed.get()) return@withLock false
                networkMonitor.start()
                startStats()
                startAutoHealing()
                startRelayEntitlementWatchdog(selected, prepared)
                connectionExpiryDeadline?.let(::scheduleProfileExpiry)
                VpnStateStore.set(
                    coreOwner,
                    VpnSnapshot(
                        state = VpnConnectionState.CONNECTED,
                        engine = selected.engine,
                        subscriptionId = profile.subscriptionId,
                        serverId = selected.id,
                        serverName = selected.name,
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
            AppLogger.i(LOG_TAG, "VPN connected successfully")
        } catch (error: CancellationException) {
            stopCoreAndTun()
            throw error
        } catch (error: Throwable) {
            stopCoreAndTun()
            AppLogger.e(LOG_TAG, "VPN startup failed", error)
            val failure = when (error) {
                is UnsatisfiedLinkError -> VpnFailure.CORE_UNAVAILABLE
                is TunnelEngineUnavailableException -> VpnFailure.CORE_UNAVAILABLE
                is TunnelEngineFailureException -> when (error.code) {
                    "relay_native_missing", "relay_process_start_failed" ->
                        VpnFailure.CORE_UNAVAILABLE
                    "relay_credential_expired" -> VpnFailure.INVALID_PROFILE
                    else -> VpnFailure.NETWORK
                }
                is TunnelNetworkRequirementException -> VpnFailure.NETWORK_REQUIREMENT
                is NetworkSetupException -> VpnFailure.NETWORK
                is SecurityException -> VpnFailure.PERMISSION_REVOKED
                else -> VpnFailure.INVALID_PROFILE
            }
            val detail = when {
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
            if (!enteredLockdown) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun stopConnection(
        stopService: Boolean,
        preserveError: Boolean = false,
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
        autoHealingJob?.cancel()
        autoHealingJob = null
        relayEntitlementWatchdogJob?.cancel()
        relayEntitlementWatchdogJob = null
        networkMonitor.stop(releaseCellular = false)
        stopCoreAndTun()
        runCatching { container.trafficHistoryStore.flush() }
        if (!preserveError) {
            VpnStateStore.set(coreOwner, VpnSnapshot())
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    private fun stopCoreAndTun() {
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
        currentEngine = null
        currentServer = null
        currentSubscriptionId = null
        currentServerName = null
        currentNetwork = null
        underlyingNetwork.set(null)
    }

    private fun establishTun(
        serverName: String,
        tunPlan: TunPlan,
    ): ParcelFileDescriptor {
        val useNativeExclusions = VpnRoutes.supportsNativeExclusions()
        return try {
            establishTun(serverName, tunPlan, useNativeExclusions)
        } catch (error: RuntimeException) {
            if (!VpnRoutes.shouldRetryWithCompatibleRoutes(useNativeExclusions, error)) {
                throw error
            }
            AppLogger.w(
                LOG_TAG,
                "Android rejected native VPN route exclusions; retrying with compatible routes",
                error,
            )
            establishTun(serverName, tunPlan, useNativeExclusions = false)
        }
    }

    private fun establishTun(
        serverName: String,
        tunPlan: TunPlan,
        useNativeExclusions: Boolean,
    ): ParcelFileDescriptor {
        val configureIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val splitMode = container.settings.splitTunnelMode.value
        val splitPackages = container.settings.splitTunnelPackages.value

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
                VpnRoutes.apply(this, useNativeExclusions)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(false)
                }
                when (splitMode) {
                    SplitTunnelMode.DISALLOWED -> {
                        splitPackages.forEach { pkg ->
                            runCatching { addDisallowedApplication(pkg) }
                        }
                    }
                    SplitTunnelMode.ALLOWED -> {
                        if (splitPackages.isNotEmpty()) {
                            splitPackages.forEach { pkg ->
                                runCatching { addAllowedApplication(pkg) }
                            }
                        }
                    }
                    SplitTunnelMode.OFF -> Unit
                }
            }
            .establish()
            ?: throw NetworkSetupException("Android denied the VPN interface")
    }

    private fun xrayTunPlan(
        primaryDns: String,
        secondaryDns: String,
        primaryDnsIpv6: String,
        secondaryDnsIpv6: String,
    ) = TunPlan(
        mtu = TUN_MTU,
        addresses = listOf(
            TunAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX),
            TunAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX),
        ),
        dnsServers = listOf(primaryDns, secondaryDns, primaryDnsIpv6, secondaryDnsIpv6),
    )

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

    private fun onNetworkAvailable(network: Network) {
        if (destroyed.get()) return
        serviceScope.launch {
            connectionMutex.withLock {
                if (destroyed.get()) return@withLock
                if (!coreRunning || currentNetwork == network) return@withLock
                val server = currentServer ?: return@withLock
                if (!networkMonitor.isCompatible(network, server.networkRequirement)) {
                    return@withLock
                }
                val previous = currentNetwork
                if (previous != null &&
                    networkMonitor.preference(network) <= networkMonitor.preference(previous)
                ) {
                    return@withLock
                }
                if (!setUnderlyingNetworks(arrayOf(network))) return@withLock
                currentNetwork = network
                underlyingNetwork.set(network)
                AppLogger.i(LOG_TAG, "Underlying network changed, triggering seamless reconnect")
                scheduleReconnectLocked()
            }
        }
    }

    private fun onNetworkLost(network: Network) {
        if (destroyed.get()) return
        serviceScope.launch {
            connectionMutex.withLock {
                if (destroyed.get()) return@withLock
                if (!coreRunning || currentNetwork != network) return@withLock
                val requirement = currentServer?.networkRequirement ?: TunnelNetworkRequirement.ANY
                val replacement = networkMonitor.activeNetwork(requirement)?.takeIf { it != network }
                if (replacement != null && setUnderlyingNetworks(arrayOf(replacement))) {
                    currentNetwork = replacement
                    underlyingNetwork.set(replacement)
                    AppLogger.i(LOG_TAG, "Underlying network lost, switched to fallback network")
                    scheduleReconnectLocked()
                    return@withLock
                }
                currentNetwork = null
                underlyingNetwork.set(null)
                reconnectJob?.cancel()
                reconnectJob = null
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

    private fun scheduleReconnect() {
        if (destroyed.get()) return
        serviceScope.launch {
            connectionMutex.withLock {
                if (destroyed.get()) return@withLock
                scheduleReconnectLocked()
            }
        }
    }

    private fun scheduleReconnectLocked() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DEBOUNCE_MS)
            connectionMutex.withLock {
                if (!coreRunning) return@withLock
                val request = currentEngineRequest ?: return@withLock
                val engine = currentEngine ?: return@withLock
                val server = currentServer ?: return@withLock
                val previousPrepared = currentPreparedSession ?: return@withLock
                VpnStateStore.update(coreOwner) {
                    it.copy(state = VpnConnectionState.RECONNECTING)
                }
                showForeground(VpnConnectionState.RECONNECTING, currentServerName)
                try {
                    relayEntitlementWatchdogJob?.cancel()
                    relayEntitlementWatchdogJob = null
                    engine.stop(coreOwner, previousPrepared, coreLease)
                    coreRunning = false
                    coreLease = null
                    currentPreparedSession = null
                    check(!destroyed.get()) { "VPN service was destroyed during reconnect" }
                    val network = currentNetwork
                        ?: throw NetworkSetupException("No usable underlying network")
                    enforceNetworkRequirement(server, network)
                    underlyingNetwork.set(network)
                    val dnsProvider = container.settings.dnsProvider.value
                    val primaryDns = if (dnsProvider == DnsProvider.CUSTOM) {
                        container.settings.customDnsIpv4.value.trim().ifBlank { dnsProvider.primaryIpv4 }
                    } else {
                        dnsProvider.primaryIpv4
                    }
                    val prepared = engine.prepare(
                        owner = coreOwner,
                        request = request,
                        environment = TunnelEngineEnvironment(
                            network = network,
                            protector = fileDescriptorProtector(network),
                            dnsServer = "$primaryDns:53",
                            terminalFailureHandler = ::onTunnelEngineTerminalFailure,
                        ),
                    )
                    val acceptedPrepared = lifecycleGate.withLock {
                        if (destroyed.get()) {
                            false
                        } else {
                            currentPreparedSession = prepared
                            true
                        }
                    }
                    if (!acceptedPrepared) {
                        runCatching { engine.stop(coreOwner, prepared) }
                        return@withLock
                    }
                    val previousTun = tunInterface
                        ?: throw NetworkSetupException("VPN interface is unavailable")
                    val activeTun = if (prepared.tunPlan == previousPrepared.tunPlan) {
                        previousTun
                    } else {
                        val replacement = establishTun(
                            serverName = server.name,
                            tunPlan = prepared.tunPlan,
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
                    coreLease = engine.start(
                        owner = coreOwner,
                        prepared = prepared,
                        tun = AndroidTunnelFileDescriptorHandle(activeTun),
                    )
                    coreRunning = true
                    val published = lifecycleGate.withLock {
                        if (destroyed.get()) return@withLock false
                        VpnStateStore.update(coreOwner) {
                            it.copy(state = VpnConnectionState.CONNECTED, failure = null)
                        }
                        startRelayEntitlementWatchdog(server, prepared)
                        showForeground(VpnConnectionState.CONNECTED, currentServerName)
                        true
                    }
                    if (!published) {
                        stopCoreAndTun()
                        return@withLock
                    }
                    AppLogger.i(LOG_TAG, "VPN successfully reconnected")
                } catch (error: Throwable) {
                    coreRunning = false
                    networkMonitor.stop(releaseCellular = false)
                    statsJob?.cancel()
                    statsJob = null
                    autoHealingJob?.cancel()
                    autoHealingJob = null
                    relayEntitlementWatchdogJob?.cancel()
                    relayEntitlementWatchdogJob = null
                    stopCoreAndTun()
                    AppLogger.e(LOG_TAG, "VPN reconnect failed", error)
                    VpnStateStore.update(coreOwner) {
                        it.copy(
                            state = VpnConnectionState.ERROR,
                            failure = when (error) {
                                is TunnelNetworkRequirementException ->
                                    VpnFailure.NETWORK_REQUIREMENT
                                is TunnelEngineUnavailableException -> VpnFailure.CORE_UNAVAILABLE
                                is TunnelEngineFailureException -> when (error.code) {
                                    "relay_native_missing", "relay_process_start_failed" ->
                                        VpnFailure.CORE_UNAVAILABLE
                                    "relay_credential_expired" -> VpnFailure.INVALID_PROFILE
                                    else -> VpnFailure.NETWORK
                                }
                                else -> VpnFailure.NETWORK
                            },
                            failureDetail = if (error is TunnelEngineFailureException) {
                                relayFailureDetail(error.code)
                            } else {
                                error.message?.takeIf { it.isNotBlank() }
                                    ?.take(MAX_FAILURE_DETAIL_LENGTH)
                            },
                        )
                    }
                    val enteredLockdown = enterKillSwitchLockdownLocked(
                        error.message?.takeIf { it.isNotBlank() }?.take(MAX_FAILURE_DETAIL_LENGTH),
                    )
                    if (!enteredLockdown) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun scheduleProfileExpiry(deadline: MonotonicCredentialDeadline) {
        profileExpiryJob?.cancel()
        profileExpiryJob = serviceScope.launch {
            while (true) {
                val remainingMs = deadline.remainingMillis(SystemClock.elapsedRealtime())
                if (remainingMs <= 0L) break
                delay(remainingMs)
            }
            profileExpiryJob = null
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
            if (!enteredLockdown) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

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
        if (server.engine != TunnelEngineKind.LEVIK_RELAY) {
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
                        currentEngine?.kind == TunnelEngineKind.LEVIK_RELAY &&
                        currentPreparedSession === prepared
                }
                if (!stillCurrent) return@launch
                if (refreshRelayEntitlementActive(subscriptionId) == false) {
                    requestRelayFailClosed(
                        VpnFailure.INVALID_PROFILE,
                        getString(R.string.relay_entitlement_revoked),
                    )
                    return@launch
                }
            }
        }
    }

    /** Null means the authoritative account check was temporarily unavailable. */
    private suspend fun refreshRelayEntitlementActive(subscriptionId: String): Boolean? = try {
        val now = Instant.now()
        val subscription = container.repository.refreshAccount().subscriptions
            .firstOrNull { it.uuid == subscriptionId }
        subscription?.isActiveAt(now) == true &&
            subscription.capabilities.whitelistRelay
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    private fun onTunnelEngineTerminalFailure(code: String) {
        if (!code.matches(Regex("^[a-z0-9_]{1,96}$"))) return
        AppLogger.w(LOG_TAG, "Relay engine terminated with stable code: $code")
        val failure = if (code == "relay_credential_expired") {
            VpnFailure.INVALID_PROFILE
        } else {
            VpnFailure.NETWORK
        }
        requestRelayFailClosed(failure, relayFailureDetail(code))
    }

    private fun requestRelayFailClosed(
        failure: VpnFailure,
        detail: String,
    ) {
        serviceScope.launch {
            connectionMutex.withLock {
                if (!coreRunning || currentEngine?.kind != TunnelEngineKind.LEVIK_RELAY) {
                    return@withLock
                }
                reconnectJob?.cancel()
                reconnectJob = null
                statsJob?.cancel()
                statsJob = null
                profileExpiryJob?.cancel()
                profileExpiryJob = null
                autoHealingJob?.cancel()
                autoHealingJob = null
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
                        failure = failure,
                        failureDetail = detail.take(MAX_FAILURE_DETAIL_LENGTH),
                    ),
                )
                stopCoreAndTun()
                val enteredLockdown = enterKillSwitchLockdownLocked(detail)
                if (!enteredLockdown) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun relayFailureDetail(code: String): String = when (code) {
        "relay_credential_expired" -> getString(R.string.relay_credential_expired)
        "relay_native_missing", "relay_process_start_failed" ->
            getString(R.string.relay_native_unavailable)
        else -> getString(R.string.relay_runtime_stopped)
    }

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
                    primaryDnsIpv6 = dnsProvider.primaryIpv6,
                    secondaryDnsIpv6 = dnsProvider.secondaryIpv6,
                ),
            )
            val engine = container.tunnelEngineRegistry.require(TunnelEngineKind.XRAY)
            val prepared = engine.prepare(
                owner = coreOwner,
                request = request,
                environment = TunnelEngineEnvironment(
                    network = null,
                    protector = TunnelFileDescriptorProtector { false },
                    dnsServer = "${dnsProvider.primaryIpv4}:53",
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
        if (!container.settings.autoHealingEnabled.value) return
        autoHealingJob = serviceScope.launch {
            var consecutiveFailures = 0
            while (coreRunning) {
                delay(AUTO_HEALING_INTERVAL_MS)
                if (!coreRunning) break
                val isAlive = checkConnectivity()
                if (isAlive) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    AppLogger.w(LOG_TAG, "Auto-healing health check failed ($consecutiveFailures/2)")
                    if (consecutiveFailures >= 2) {
                        consecutiveFailures = 0
                        val fallbackSuccess = tryAutoFallback()
                        if (!fallbackSuccess) {
                            AppLogger.w(LOG_TAG, "Triggering auto-healing reconnect for stalled tunnel")
                            scheduleReconnect()
                        }
                    }
                }
            }
        }
    }

    private suspend fun tryAutoFallback(): Boolean {
        if (!container.settings.autoFallbackServer.value) return false
        val profile = runCatching { readPreparedProfile() }.getOrNull() ?: return false
        if (profile.servers.size <= 1) return false

        val currentId = readSelectedServerId() ?: profile.servers.firstOrNull()?.id ?: return false
        val candidates = profile.servers.filter { server ->
            server.id != currentId &&
                server.isEligibleForAutomaticSelection() &&
                !server.isMobileServer()
        }
        if (candidates.isEmpty()) return false

        AppLogger.i(LOG_TAG, "Current server seems stalled, testing ${candidates.size} fallback servers")
        var targetServer = candidates.first()
        val alive = candidates.mapNotNull { s ->
            val p = runCatching { ServerPinger.measure(s) }.getOrNull()
            if (p != null) s to p else null
        }
        if (alive.isNotEmpty()) {
            targetServer = alive.minByOrNull { it.second }?.first ?: targetServer
        }

        AppLogger.i(LOG_TAG, "Auto-failover selected a responsive server")
        container.secureStore.put(SecureFileStore.SELECTED_SERVER, targetServer.id.encodeToByteArray())

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopConnection(stopService = false)
            connect()
        }
        return true
    }

    private suspend fun pauseConnection(minutes: Int) = connectionMutex.withLock {
        pauseJob?.cancel()
        connectionJob?.cancel()
        reconnectJob?.cancel()
        statsJob?.cancel()
        profileExpiryJob?.cancel()
        autoHealingJob?.cancel()
        relayEntitlementWatchdogJob?.cancel()
        relayEntitlementWatchdogJob = null
        lockdownActive = false
        networkMonitor.stop(releaseCellular = false)
        val serverNameBeforePause = currentServerName
        stopCoreAndTun()

        val pauseDurationMs = minutes * 60_000L
        val pauseEndsAt = System.currentTimeMillis() + pauseDurationMs
        container.settings.setPausedUntilMs(pauseEndsAt)

        val initialRemaining = (pauseDurationMs / 1000L)
        VpnStateStore.set(
            coreOwner,
            VpnSnapshot(
                state = VpnConnectionState.PAUSED,
                serverName = serverNameBeforePause,
                pausedRemainingSeconds = initialRemaining,
            ),
        )
        showForeground(VpnConnectionState.PAUSED, serverNameBeforePause, initialRemaining)

        pauseJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                val remaining = ((pauseEndsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                if (remaining <= 0) {
                    AppLogger.i(LOG_TAG, "VPN pause expired, automatically resuming connection")
                    container.settings.setPausedUntilMs(0L)
                    pauseJob = null
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
                showForeground(VpnConnectionState.PAUSED, serverNameBeforePause, remaining)
            }
        }
    }

    private suspend fun resumeConnection() {
        pauseJob?.cancel()
        pauseJob = null
        container.settings.setPausedUntilMs(0L)
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            connect()
        }
    }

    private suspend fun checkConnectivity(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://1.1.1.1/cdn-cgi/trace")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                useCaches = false
                requestMethod = "GET"
            }
            conn.inputStream.use { it.read() }
            conn.disconnect()
            true
        } catch (_: Exception) {
            false
        }
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
    ) {
        val notification = buildNotification(state, serverName, remainingSeconds)
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
            )
            VpnConnectionState.PAUSED -> getString(
                R.string.vpn_notification_paused,
                formattedTime,
            )
            VpnConnectionState.RECONNECTING -> getString(
                R.string.vpn_notification_reconnecting,
            )
            VpnConnectionState.CONNECTING -> getString(R.string.vpn_notification_connecting)
            VpnConnectionState.LOCKDOWN -> getString(R.string.vpn_notification_lockdown)
            else -> getString(R.string.vpn_notification_idle)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
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

        private const val NOTIFICATION_CHANNEL_ID = "levik_vpn_connection"
        private const val LOG_TAG = "LevikVpnService"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_OPEN_APP = 4102
        private const val REQUEST_DISCONNECT = 4103
        private const val REQUEST_RESUME = 4104
        private const val REQUEST_PAUSE = 4105
        private const val DEFAULT_PAUSE_MINUTES = 15
        private const val RECONNECT_DEBOUNCE_MS = 750L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val AUTO_HEALING_INTERVAL_MS = 30_000L
        private const val RELAY_ENTITLEMENT_WATCHDOG_INTERVAL_MS = 120_000L
        private const val WAKELOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private const val MAX_FAILURE_DETAIL_LENGTH = 300
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "172.30.0.2"
        private const val TUN_IPV4_PREFIX = 30
        private const val TUN_IPV6_ADDRESS = "2600:1900:4000:5255::2"
        private const val TUN_IPV6_PREFIX = 64
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
