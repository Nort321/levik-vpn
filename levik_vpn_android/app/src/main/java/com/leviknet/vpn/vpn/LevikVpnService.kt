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
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.time.Duration
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
import libXray.DialerController

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
    private val controller = object : DialerController {
        override fun protectFd(fd: Long): Boolean {
            if (fd !in 0..Int.MAX_VALUE.toLong()) return false
            val socketFd = fd.toInt()
            val network = underlyingNetwork.get() ?: return false
            if (!protect(socketFd)) return false
            return runCatching {
                val pfd = ParcelFileDescriptor.adoptFd(socketFd)
                try {
                    network.bindSocket(pfd.fileDescriptor)
                } finally {
                    pfd.detachFd() // Keep the underlying native socket open!
                }
                true
            }.getOrDefault(false)
        }
    }
    private val networkMonitor by lazy {
        NetworkMonitor(
            context = this,
            handleAvailable = ::onNetworkAvailable,
            handleLost = ::onNetworkLost,
        )
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var currentConfig: String? = null
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

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(LOG_TAG, "LevikVpnService onCreate")
        container.xrayRuntime.claimOwner(coreOwner)
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
        container.xrayRuntime.retireOwner(coreOwner)
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
            networkMonitor.stop()
            underlyingNetwork.set(null)
            val capturedLease = coreLease
            val capturedTun = tunInterface
            coreRunning = false
            coreLease = null
            tunInterface = null
            currentConfig = null
            currentServerName = null
            currentNetwork = null
            NativeCleanup(capturedLease, capturedTun)
        }
        serviceScope.cancel()
        container.nativeCleanupScope.launch {
            try {
                runCatching { container.xrayRuntime.stop(coreOwner, cleanup.lease) }
            } finally {
                runCatching { cleanup.tunInterface?.close() }
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

            AppLogger.i(LOG_TAG, "Establishing connection to server: ${selected.name} (${selected.id})")

            currentNetwork = networkMonitor.activeNetwork()
                ?: throw NetworkSetupException("No validated underlying network")
            val tun = try {
                establishTun(selected.name)
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
            if (!setUnderlyingNetworks(arrayOf(requireNotNull(currentNetwork)))) {
                throw NetworkSetupException(
                    "Unable to bind the VPN to its underlying network",
                )
            }
            underlyingNetwork.set(currentNetwork)
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

            val config = XrayConfigBuilder(container.json).build(
                profile = profile,
                selectedServerId = selected.id,
                tunFileDescriptor = tun.fd,
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
            currentConfig = config
            currentServerName = selected.name

            check(!destroyed.get()) { "VPN service was destroyed during startup" }
            coreLease = container.xrayRuntime.start(
                owner = coreOwner,
                configJson = config,
                controller = controller,
                dnsServer = "$primaryDns:53",
            )
            coreRunning = true
            val published = lifecycleGate.withLock {
                if (destroyed.get()) return@withLock false
                networkMonitor.start()
                startStats()
                startAutoHealing()
                profile.subscriptionExpiresAt?.let(::scheduleProfileExpiry)
                VpnStateStore.set(
                    coreOwner,
                    VpnSnapshot(
                        state = VpnConnectionState.CONNECTED,
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
            AppLogger.i(LOG_TAG, "Connected successfully to ${selected.name}")
        } catch (error: CancellationException) {
            stopCoreAndTun()
            throw error
        } catch (error: Throwable) {
            stopCoreAndTun()
            AppLogger.e(LOG_TAG, "VPN startup failed", error)
            val failure = when (error) {
                is UnsatisfiedLinkError -> VpnFailure.CORE_UNAVAILABLE
                is NetworkSetupException -> VpnFailure.NETWORK
                is SecurityException -> VpnFailure.PERMISSION_REVOKED
                else -> VpnFailure.INVALID_PROFILE
            }
            val detail = when {
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
        networkMonitor.stop()
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
        if (coreRunning) {
            runCatching { container.xrayRuntime.stop(coreOwner, coreLease) }
        }
        coreRunning = false
        coreLease = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        currentConfig = null
        currentServerName = null
        currentNetwork = null
        underlyingNetwork.set(null)
    }

    private fun establishTun(serverName: String): ParcelFileDescriptor {
        val useNativeExclusions = VpnRoutes.supportsNativeExclusions()
        return try {
            establishTun(serverName, useNativeExclusions)
        } catch (error: RuntimeException) {
            if (!VpnRoutes.shouldRetryWithCompatibleRoutes(useNativeExclusions, error)) {
                throw error
            }
            AppLogger.w(
                LOG_TAG,
                "Android rejected native VPN route exclusions; retrying with compatible routes",
                error,
            )
            establishTun(serverName, useNativeExclusions = false)
        }
    }

    private fun establishTun(
        serverName: String,
        useNativeExclusions: Boolean,
    ): ParcelFileDescriptor {
        val configureIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dnsProvider = container.settings.dnsProvider.value
        val primaryDns = if (dnsProvider == DnsProvider.CUSTOM) {
            container.settings.customDnsIpv4.value.trim().ifBlank { dnsProvider.primaryIpv4 }
        } else {
            dnsProvider.primaryIpv4
        }
        val secondaryDns = if (dnsProvider == DnsProvider.CUSTOM) "8.8.8.8" else dnsProvider.secondaryIpv4
        val primaryDnsIpv6 = dnsProvider.primaryIpv6
        val secondaryDnsIpv6 = dnsProvider.secondaryIpv6
        val splitMode = container.settings.splitTunnelMode.value
        val splitPackages = container.settings.splitTunnelPackages.value

        return Builder()
            .setSession(getString(R.string.vpn_session_name, serverName))
            .setConfigureIntent(configureIntent)
            .setMtu(TUN_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)
            .addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
            .addDnsServer(primaryDns)
            .addDnsServer(secondaryDns)
            .addDnsServer(primaryDnsIpv6)
            .addDnsServer(secondaryDnsIpv6)
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

    private fun onNetworkAvailable(network: Network) {
        if (destroyed.get()) return
        serviceScope.launch {
            connectionMutex.withLock {
                if (destroyed.get()) return@withLock
                if (!coreRunning || currentNetwork == network) return@withLock
                val previous = currentNetwork
                if (previous != null &&
                    networkMonitor.preference(network) <= networkMonitor.preference(previous)
                ) {
                    return@withLock
                }
                if (!setUnderlyingNetworks(arrayOf(network))) return@withLock
                currentNetwork = network
                underlyingNetwork.set(network)
                AppLogger.i(LOG_TAG, "Network switched to $network, triggering seamless reconnect")
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
                val replacement = networkMonitor.activeNetwork()?.takeIf { it != network }
                if (replacement != null && setUnderlyingNetworks(arrayOf(replacement))) {
                    currentNetwork = replacement
                    underlyingNetwork.set(replacement)
                    AppLogger.i(LOG_TAG, "Network lost, switched to fallback $replacement")
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
                val config = currentConfig ?: return@withLock
                VpnStateStore.update(coreOwner) {
                    it.copy(state = VpnConnectionState.RECONNECTING)
                }
                showForeground(VpnConnectionState.RECONNECTING, currentServerName)
                try {
                    container.xrayRuntime.stop(coreOwner, coreLease)
                    coreRunning = false
                    check(!destroyed.get()) { "VPN service was destroyed during reconnect" }
                    val dnsProvider = container.settings.dnsProvider.value
                    val primaryDns = if (dnsProvider == DnsProvider.CUSTOM) {
                        container.settings.customDnsIpv4.value.trim().ifBlank { dnsProvider.primaryIpv4 }
                    } else {
                        dnsProvider.primaryIpv4
                    }
                    coreLease = container.xrayRuntime.start(
                        owner = coreOwner,
                        configJson = config,
                        controller = controller,
                        dnsServer = "$primaryDns:53",
                    )
                    coreRunning = true
                    val published = lifecycleGate.withLock {
                        if (destroyed.get()) return@withLock false
                        VpnStateStore.update(coreOwner) {
                            it.copy(state = VpnConnectionState.CONNECTED, failure = null)
                        }
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
                    networkMonitor.stop()
                    statsJob?.cancel()
                    statsJob = null
                    autoHealingJob?.cancel()
                    autoHealingJob = null
                    stopCoreAndTun()
                    AppLogger.e(LOG_TAG, "VPN reconnect failed", error)
                    VpnStateStore.update(coreOwner) {
                        it.copy(
                            state = VpnConnectionState.ERROR,
                            failure = VpnFailure.NETWORK,
                            failureDetail = error.message?.takeIf { it.isNotBlank() }
                                ?.take(MAX_FAILURE_DETAIL_LENGTH),
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

    private fun scheduleProfileExpiry(expiresAt: String) {
        profileExpiryJob?.cancel()
        val remainingMs = Duration.between(Instant.now(), Instant.parse(expiresAt))
            .toMillis()
            .coerceAtLeast(0)
        profileExpiryJob = serviceScope.launch {
            delay(remainingMs)
            profileExpiryJob = null
            AppLogger.w(LOG_TAG, "Subscription expired, tearing down tunnel")
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

    private fun enterKillSwitchLockdownLocked(failureDetail: String?): Boolean {
        if (lockdownActive) return true
        if (!container.settings.killSwitchEnabled.value) return false
        return try {
            stopCoreAndTun()
            val tun = establishTun(getString(R.string.vpn_kill_switch_session))
            tunInterface = tun
            currentConfig = null
            val config = XrayConfigBuilder(container.json).buildKillSwitchConfig(tun.fd)
            check(!destroyed.get()) { "VPN service was destroyed during lockdown" }
            coreLease = container.xrayRuntime.start(
                owner = coreOwner,
                configJson = config,
                controller = controller,
                dnsServer = "${DnsProvider.CLOUDFLARE.primaryIpv4}:53",
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

        AppLogger.i(LOG_TAG, "Server $currentId seems stalled, testing ${candidates.size} fallback servers")
        var targetServer = candidates.first()
        val alive = candidates.mapNotNull { s ->
            val p = runCatching { ServerPinger.measure(s.outbound) }.getOrNull()
            if (p != null) s to p else null
        }
        if (alive.isNotEmpty()) {
            targetServer = alive.minByOrNull { it.second }?.first ?: targetServer
        }

        AppLogger.i(LOG_TAG, "Auto-failover: switching from $currentId to ${targetServer.id} (${targetServer.name})")
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
        lockdownActive = false
        networkMonitor.stop()
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
    val lease: Long?,
    val tunInterface: ParcelFileDescriptor?,
)

private class NetworkSetupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
