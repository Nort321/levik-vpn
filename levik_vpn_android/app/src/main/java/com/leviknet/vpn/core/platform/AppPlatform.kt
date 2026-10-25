package com.leviknet.vpn.core.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import com.leviknet.vpn.core.auth.LoginApproval
import com.leviknet.vpn.core.auth.PendingApproval
import com.leviknet.vpn.core.drop.DropEngine
import com.leviknet.vpn.core.drop.DropNotifications
import com.leviknet.vpn.core.drop.DropTransferService
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.ServiceEndpoints
import com.leviknet.vpn.core.notification.AppIconArtwork
import com.leviknet.vpn.core.update.AppUpdateManager
import com.leviknet.vpn.data.AppIconManager
import com.leviknet.vpn.data.AppRepository
import com.leviknet.vpn.data.AppSettings
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.vpn.ProtocolHistoryStore
import com.leviknet.vpn.vpn.ServerPinger
import com.leviknet.vpn.vpn.SmartAuto
import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnController
import com.leviknet.vpn.vpn.isEligibleForAutomaticSelection
import com.leviknet.vpn.vpn.isMobileServer
import com.leviknet.vpn.vpn.telemetryProtocol
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull

/**
 * Keeps the app in step with the website and the user's other apps:
 * announcements and protocol advice from the anonymous configuration, and
 * the account's shared settings, docs/app-platform.md.
 */
class AppPlatform(
    private val context: Context,
    private val settings: AppSettings,
    private val repository: AppRepository,
    private val vpnController: VpnController,
    private val directNetwork: () -> Network?,
    private val scope: CoroutineScope,
    private val json: Json,
    updateManager: AppUpdateManager? = null,
    diagnosticsNote: (suspend () -> String)? = null,
    /** LevikDrop (Direct builds), docs/levik-drop.md. */
    val drop: DropEngine? = null,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val configFile = File(context.noBackupFilesDir, CONFIG_FILE)
    private val configClient = RemoteConfigClient(BuildConfig.VERSION_NAME)
    private val configMutex = Mutex()
    private val syncMutex = Mutex()
    private val favoritesMutex = Mutex()

    @Volatile
    private var stored: StoredRemoteConfig? = null

    @Volatile
    private var lastSyncAtMs = 0L

    private val foreground = MutableStateFlow(false)

    /** What the server holds for this phone; null when it holds nothing. */
    @Volatile
    private var sentPresence: PresenceReport? = null

    private val approvalsMutex = Mutex()
    private val noticesMutex = Mutex()
    private val shownNotices = LinkedHashSet<String>()
    private var approvalsExpiry: Job? = null

    private val mutableApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())

    /** Sign-ins on the user's other devices waiting for this phone (Direct builds). */
    val approvals: StateFlow<List<PendingApproval>> = mutableApprovals.asStateFlow()

    private val maintenanceMutex = Mutex()
    private val rulesMutex = Mutex()
    private val idleMeter = IdleMeter()

    private val mutableMaintenance = MutableStateFlow<List<MaintenanceWindow>>(emptyList())

    /** Planned work on servers (Direct builds); the server list marks them and automatic choice skips them. */
    val maintenance: StateFlow<List<MaintenanceWindow>> = mutableMaintenance.asStateFlow()

    private val mutableServerSwitches = MutableSharedFlow<MaintenanceSwitch>(extraBufferCapacity = 1)

    /** The app moved the connection by itself: before a server's maintenance, or for the remote. */
    val serverSwitches: SharedFlow<MaintenanceSwitch> = mutableServerSwitches.asSharedFlow()

    /** Set while the app is on the screen: refreshes the profile the way the refresh button does. */
    @Volatile
    var profileRefresher: (suspend () -> Unit)? = null

    /** The remote (Direct builds), docs/app-platform.md "Remote". */
    val remote: RemoteControl? = if (REMOTE_ENABLED && diagnosticsNote != null) {
        RemoteControl(
            context = context,
            settings = settings,
            repository = repository,
            vpnController = vpnController,
            updateManager = updateManager,
            actions = object : RemoteActions {
                override suspend fun moveToFastestServer() = this@AppPlatform.moveToFastestServer()
                override suspend fun refreshProfile() {
                    profileRefresher?.invoke() ?: repository.refreshAccount()
                }
                override suspend fun diagnosticsNote(): String = diagnosticsNote()
            },
            scope = scope,
            notify = ::postMessage,
        )
    } else {
        null
    }

    private val mutableAnnouncements = MutableStateFlow<List<AppAnnouncement>>(emptyList())
    val announcements: StateFlow<List<AppAnnouncement>> = mutableAnnouncements.asStateFlow()

    private val mutableSyncEnabled = MutableStateFlow(preferences.getBoolean(SYNC_ENABLED, true))
    val syncEnabled: StateFlow<Boolean> = mutableSyncEnabled.asStateFlow()

    private val mutableSyncedAt = MutableStateFlow(preferences.getLong(SYNCED_AT, 0L).takeIf { it > 0L })
    val syncedAt: StateFlow<Long?> = mutableSyncedAt.asStateFlow()

    /** The local rollout salt; it never leaves the phone. */
    private val salt: String by lazy {
        preferences.getString(SALT, null)?.takeIf { SALT_PATTERN.matches(it) } ?: ByteArray(16)
            .also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
            .also { preferences.edit(commit = true) { putString(SALT, it) } }
    }

    fun start() {
        scope.launch {
            stored = loadStored()
            publishAnnouncements()
            publishMaintenance()
            refreshConfigIfStale()
            while (true) {
                delay((stored?.config?.refreshAfterSeconds ?: DEFAULT_REFRESH_S) * 1_000L)
                refreshConfig()
                applyServiceRoutes()
            }
        }
        scope.launch {
            repository.session.collect { status ->
                when (status) {
                    SessionStatus.Authenticated -> {
                        drop?.start()
                        syncSettings()
                    }
                    SessionStatus.SignedOut -> {
                        forgetSyncState()
                        forgetApprovals()
                        remote?.forget()
                        drop?.signedOut()
                    }
                    SessionStatus.Loading -> Unit
                }
            }
        }
        if (SHARE_FAVORITES) {
            scope.launch {
                // Starring a server here, a new server list or the account's list: bring both sides together.
                combine(settings.favoriteServerIds, repository.tunnelProfile, settings.favoriteNodes) { _, profile, _ ->
                    profile?.servers
                }.collectLatest { servers -> if (servers != null) reconcileFavorites(servers) }
            }
        }
        if (LIVE_ENABLED) {
            scope.launch {
                // Online while on the screen or while the VPN runs; offline otherwise.
                combine(
                    repository.session,
                    foreground,
                    vpnController.state.map { it.state }.distinctUntilChanged(),
                ) { session, visible, state ->
                    session == SessionStatus.Authenticated && PresencePolicy.online(visible, state)
                }.distinctUntilChanged().collectLatest { online ->
                    // LevikDrop can receive exactly while the event stream is open.
                    drop?.setOnline(online)
                    if (online) {
                        coroutineScope {
                            launch { runEventStream() }
                            launch { runPresence() }
                        }
                    } else {
                        withdrawPresence()
                    }
                }
            }
        }
        drop?.let { engine ->
            scope.launch {
                // Offers and finished transfers reach the user outside the app; on the screen the app shows them.
                engine.notices.collect { notice -> if (!foreground.value) DropNotifications.notice(context, notice) }
            }
            scope.launch {
                // A transfer the user started or accepted goes on with the app in the background.
                engine.snapshot.map { snapshot -> snapshot.transfers.any { it.held } }
                    .distinctUntilChanged()
                    .collect { held -> if (held) DropTransferService.start(context) }
            }
        }
        if (HOME_ENABLED) {
            scope.launch {
                HomeWatcher(context, settings, repository, vpnController, directNetwork, foreground.asStateFlow()).run()
            }
        }
        if (MAINTENANCE_ENABLED) {
            scope.launch {
                vpnController.state.collect { snapshot ->
                    if (snapshot.state == VpnConnectionState.CONNECTED) {
                        idleMeter.record(snapshot.downloadedBytes + snapshot.uploadedBytes, System.currentTimeMillis())
                    } else {
                        idleMeter.reset()
                    }
                }
            }
            scope.launch {
                while (true) {
                    delay(MAINTENANCE_CHECK_MS)
                    publishMaintenance()
                    checkMaintenance()
                }
            }
        }
        scope.launch {
            // Local edits are sent shortly after the user stops changing them.
            combine(
                listOf<Flow<Any?>>(
                    settings.routingPreset, settings.automaticServer, settings.autoHealingEnabled,
                    settings.killSwitchEnabled, settings.useDoh, settings.antiDpiEnabled,
                    settings.antiDpiPackets, settings.antiDpiLength, settings.antiDpiInterval,
                    settings.favoriteNodes, settings.sharePresence,
                ),
            ) { localValues() }
                .distinctUntilChanged()
                .collectLatest { values ->
                    delay(SETTINGS_PUSH_DELAY_MS)
                    if (loadSettings(BASELINE)?.let { it != values } == true) scope.launch { syncSettings() }
                }
        }
    }

    /** The app came to the screen: catch up with what changed meanwhile. */
    fun onForeground() {
        foreground.value = true
        scope.launch {
            refreshConfigIfStale()
            if (System.currentTimeMillis() - lastSyncAtMs >= SETTINGS_SYNC_INTERVAL_MS) syncSettings()
        }
    }

    fun onBackground() {
        foreground.value = false
    }

    /** Best effort before signing out; the server forgets the phone on its own three minutes later. */
    suspend fun withdrawPresence() {
        if (sentPresence == null) return
        sentPresence = null
        try {
            repository.clearPresence()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w(TAG, "Could not clear presence: ${error.javaClass.simpleName}")
        }
    }

    fun dismissAnnouncement(id: String) {
        val dismissed = rememberedIds(preferences.getStringSet(DISMISSED, emptySet()).orEmpty(), listOf(id))
        preferences.edit(commit = true) { putStringSet(DISMISSED, dismissed) }
        publishAnnouncements()
    }

    fun flagEnabled(key: String): Boolean = RemoteConfigPolicy.flagEnabled(stored?.config, key, salt)

    fun protocolAdvice(): ProtocolAdvice? = RemoteConfigPolicy.currentAdvice(stored, System.currentTimeMillis())

    /** Server load by node id while the configuration is fresh. */
    fun nodeLoad(): Map<String, Double> = RemoteConfigPolicy.currentNodeLoad(stored, System.currentTimeMillis())

    /** Smart Auto: free servers whose protocol works on this network, docs/app-platform.md "Smart Auto". */
    val smartAuto = SmartAuto(
        context = context,
        history = ProtocolHistoryStore(context, json),
        nodeLoad = ::nodeLoad,
        advice = ::protocolAdvice,
        physicalNetwork = directNetwork,
    )

    /**
     * Asks for the configuration again when its server load is older than a
     * couple of minutes, waiting [waitMs] at most: a slow answer serves the next choice.
     */
    suspend fun refreshNodeLoad(waitMs: Long = NODE_LOAD_WAIT_MS) {
        val age = System.currentTimeMillis() - (stored?.fetchedAtMs ?: 0L)
        if (age < NODE_LOAD_MAX_AGE_MS) return
        val refresh = scope.async { refreshConfig() }
        withTimeoutOrNull(waitMs) { refresh.await() }
    }

    fun xhttpMuxHosts(): Set<String> = stored?.config?.xhttpMuxHosts.orEmpty()

    /** True while the server is under maintenance or about to be: it is not chosen automatically. */
    fun underMaintenance(server: TunnelServer, nowMs: Long = System.currentTimeMillis()): Boolean =
        ServerMaintenance.windowFor(mutableMaintenance.value, FavoriteNodes.nodeId(server), nowMs) != null

    /** Leaves out servers under maintenance, unless that would leave none. */
    fun avoidMaintenance(servers: List<TunnelServer>, nowMs: Long = System.currentTimeMillis()): List<TunnelServer> =
        ServerMaintenance.avoid(servers, mutableMaintenance.value, FavoriteNodes::nodeId, nowMs)

    /** The cabinet page signed in through a one-time link, or the plain page when that fails. */
    suspend fun cabinetUrl(target: CabinetTarget): String = try {
        repository.webHandoffUrl(target.path) ?: AppLinks.cabinetFallbackUrl(target)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppLogger.w(TAG, "Signed-in cabinet link unavailable: ${error.javaClass.simpleName}")
        AppLinks.cabinetFallbackUrl(target)
    }

    fun setSyncEnabled(enabled: Boolean) {
        if (mutableSyncEnabled.value == enabled) return
        // Turning sync on joins the account's settings; turning it off forgets unsent edits.
        preferences.edit(commit = true) {
            putBoolean(SYNC_ENABLED, enabled)
            remove(PENDING)
            remove(BASELINE)
            remove(SYNCED_AT)
        }
        mutableSyncEnabled.value = enabled
        mutableSyncedAt.value = null
        if (enabled) scope.launch { syncSettings() }
    }

    private suspend fun refreshConfigIfStale() {
        val current = stored
        val age = System.currentTimeMillis() - (current?.fetchedAtMs ?: 0L)
        if (current == null || age >= current.config.refreshAfterSeconds * 1_000L) refreshConfig()
    }

    private suspend fun refreshConfig() = configMutex.withLock {
        val physical = directNetwork()?.let { configClient.fetch(it) }
        val fetched = physical ?: configClient.fetch(null) ?: return@withLock
        // Without the physical network the request may have gone through the tunnel.
        val direct = physical != null || vpnController.state.value.state in INACTIVE_TUNNEL
        val next = RemoteConfigPolicy.merge(stored, fetched.first, fetched.second, System.currentTimeMillis(), direct)
        stored = next
        runCatching {
            withContext(Dispatchers.IO) {
                val temporary = File(configFile.path + ".tmp")
                temporary.writeText(RemoteConfigPolicy.encodeStored(next).toString())
                if (!temporary.renameTo(configFile)) temporary.delete()
            }
        }.onFailure { AppLogger.w(TAG, "Could not save app configuration") }
        publishAnnouncements()
        if (MAINTENANCE_ENABLED) {
            publishMaintenance()
            scope.launch { checkMaintenance() }
        }
    }

    /** Windows that have not ended; the server sends only those within the next day. */
    private fun publishMaintenance() {
        if (!MAINTENANCE_ENABLED) return
        val now = System.currentTimeMillis()
        val current = stored?.config?.maintenance.orEmpty().filter { it.endsAtMs > now }
        if (mutableMaintenance.value != current) mutableMaintenance.value = current
    }

    /**
     * Moves the connection off a server before its maintenance: while it is
     * idle, or two minutes before the start at the latest. The app does not
     * move back afterwards, docs/app-platform.md.
     */
    private suspend fun checkMaintenance() {
        if (!MAINTENANCE_ENABLED || !maintenanceMutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val snapshot = vpnController.state.value
            if (snapshot.state != VpnConnectionState.CONNECTED) return
            val servers = repository.tunnelProfile.value?.servers ?: return
            val current = servers.firstOrNull { it.id == snapshot.serverId } ?: return
            val move = ServerMaintenance.move(
                ServerMaintenance.windowFor(mutableMaintenance.value, FavoriteNodes.nodeId(current), now),
                now,
            )
            if (move == MaintenanceMove.STAY || (move == MaintenanceMove.WHEN_IDLE && !idleMeter.idle(now))) return
            val target = fastestOfKind(current, servers, now, includeCurrent = false) ?: run {
                AppLogger.w(TAG, "No server to move to before maintenance")
                return
            }
            AppLogger.i(TAG, "Moving off a server before its maintenance")
            repository.selectServer(target.id)
            vpnController.switchServer(target.id)
            idleMeter.reset()
            mutableServerSwitches.tryEmit(MaintenanceSwitch(current.id, target.id))
            postMessage(
                MAINTENANCE_NOTIFICATION_ID,
                context.getString(R.string.maintenance_moved_title, current.name),
                context.getString(R.string.maintenance_moved_body, target.name),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w(TAG, "Could not move before maintenance: ${error.javaClass.simpleName}")
        } finally {
            maintenanceMutex.unlock()
        }
    }

    /**
     * "It's not the VPN", docs/app-platform.md "Diagnosis": the phone's own
     * network and the website's view of it, asked through that network so the
     * server sees the operator rather than a Levik server.
     */
    suspend fun diagnosisNetwork(): Triple<LocalNetwork, Boolean, DiagnoseReport?> {
        val physical = directNetwork()
        val capabilities = physical?.let {
            context.getSystemService(android.net.ConnectivityManager::class.java)?.getNetworkCapabilities(it)
        }
        val local = when {
            physical == null -> LocalNetwork.NONE
            capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true ->
                LocalNetwork.CAPTIVE
            else -> LocalNetwork.OK
        }
        val cellular = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val report = if (local == LocalNetwork.OK) configClient.diagnose(physical) else null
        return Triple(local, cellular, report)
    }

    /** "Best server" from a diagnosis: automatic choice, and a running connection moves at once. */
    suspend fun useBestServer() {
        if (!settings.automaticServer.value) settings.setAutomaticServer(true)
        moveToFastestServer()
    }

    /** The remote asked for the automatic server: move a running connection to the fastest one. */
    private suspend fun moveToFastestServer() {
        val snapshot = vpnController.state.value
        if (snapshot.state != VpnConnectionState.CONNECTED) return
        val servers = repository.tunnelProfile.value?.servers ?: return
        val current = servers.firstOrNull { it.id == snapshot.serverId } ?: return
        val target = fastestOfKind(current, servers, System.currentTimeMillis(), includeCurrent = true) ?: return
        if (target.id == current.id) return
        repository.selectServer(target.id)
        vpnController.switchServer(target.id)
        mutableServerSwitches.tryEmit(MaintenanceSwitch(current.id, target.id))
    }

    /**
     * The best server of the same kind outside maintenance: Smart Auto for
     * regular servers, the fastest answering one for mobile servers.
     */
    private suspend fun fastestOfKind(
        current: TunnelServer,
        servers: List<TunnelServer>,
        nowMs: Long,
        includeCurrent: Boolean,
    ): TunnelServer? {
        val mobile = current.isMobileServer()
        val pool = servers.filter {
            (includeCurrent || it.id != current.id) && it.isEligibleForAutomaticSelection() &&
                it.isMobileServer() == mobile && !underMaintenance(it, nowMs)
        }
        if (pool.isEmpty()) return null
        val loadRefresh = scope.async { if (!mobile) refreshNodeLoad() }
        val measured = supervisorScope {
            pool.map { server -> async(Dispatchers.IO) { server.id to runCatching { ServerPinger.measure(server) }.getOrNull() } }
                .awaitAll()
        }.toMap()
        loadRefresh.await()
        if (!mobile) {
            // Moving a working connection only to a server that may be up.
            return smartAuto.rank(pool, measured).firstNotNullOfOrNull { ranked ->
                pool.firstOrNull { it.id == ranked.id }
                    ?.takeIf { measured[it.id] != null || !ServerPinger.silenceMeansDown(it) }
            }
        }
        val answering = pool.mapNotNull { server -> measured[server.id]?.let { server to it } }
        if (answering.isEmpty()) return null
        val advised = RemoteConfigPolicy.candidates(answering, protocolAdvice()) { it.first.telemetryProtocol().wire }
        return advised.minByOrNull { it.second }?.first
    }

    private suspend fun loadStored(): StoredRemoteConfig? = withContext(Dispatchers.IO) {
        runCatching {
            configFile.takeIf(File::isFile)?.readText()?.let(json::parseToJsonElement)
                ?.let(RemoteConfigPolicy::decodeStored)
        }.getOrNull()
    }

    private fun publishAnnouncements() {
        val dismissed = preferences.getStringSet(DISMISSED, emptySet()).orEmpty()
        val visible = RemoteConfigPolicy.visible(stored?.config, dismissed, System.currentTimeMillis())
        mutableAnnouncements.value = visible
        val notified = preferences.getStringSet(NOTIFIED, emptySet()).orEmpty()
        val fresh = visible.filter { it.notify && it.id !in notified }
        if (fresh.isEmpty()) return
        fresh.forEach(::postNotification)
        preferences.edit(commit = true) {
            putStringSet(NOTIFIED, rememberedIds(notified, fresh.map(AppAnnouncement::id)))
        }
    }

    /**
     * Stored sets have no order, so when the list grows too long the ids of
     * announcements the server no longer sends are dropped first.
     */
    private fun rememberedIds(existing: Set<String>, added: Collection<String>): Set<String> {
        val all = existing + added
        if (all.size <= MAX_REMEMBERED) return all
        val current = stored?.config?.announcements.orEmpty().map(AppAnnouncement::id).toSet()
        return (added + existing.filter { it in current }).toSet()
    }

    private fun postNotification(announcement: AppAnnouncement) {
        postMessage(NOTIFICATION_ID_BASE + (announcement.id.hashCode() and 0x3ff), announcement.title, announcement.body)
    }

    private fun postMessage(notificationId: Int, title: String, body: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_announcements),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_announcements_desc) },
        )
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, AppIconManager(context).current()))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { manager.notify(notificationId, notification) }
            .onFailure { AppLogger.w(TAG, "Could not show a message") }
    }

    /** One exchange with the account at a time; failures wait for the next attempt. */
    private suspend fun syncSettings() = syncMutex.withLock {
        if (!mutableSyncEnabled.value || repository.session.value != SessionStatus.Authenticated) return@withLock
        try {
            exchangeSettings()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w(TAG, "Settings sync failed: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun exchangeSettings() {
        repeat(MAX_EXCHANGES) {
            val current = localValues()
            // Edits made since the last exchange; a first exchange takes the account's values.
            val baseline = loadSettings(BASELINE)
            var pending = loadSettings(PENDING).orEmpty()
            if (baseline != null) pending = pending + SettingsSyncPolicy.changes(baseline, current)
            saveSettings(PENDING, pending)
            val sent = pending
            var document = SettingsSyncPolicy.parseDocument(
                if (sent.isEmpty()) repository.syncedSettings() else repository.updateSyncedSettings(sent),
            )
            if (document?.revision == 0L) {
                // Nothing saved for this account yet: this phone's settings become the shared ones.
                document = SettingsSyncPolicy.parseDocument(
                    repository.updateSyncedSettings(SettingsSyncPolicy.portable(current)),
                )
            }
            checkNotNull(document) { "Invalid settings document" }
            if (!mutableSyncEnabled.value) return
            if (baseline?.containsKey(SettingsSyncPolicy.FAVORITE_NODES) != true) {
                joinFavorites(localValues(), document.settings)?.let { pending = pending + it }
            }
            val local = localValues()
            pending = SettingsSyncPolicy.withoutConfirmed(pending, sent) + SettingsSyncPolicy.changes(current, local)
            pending = pending + SettingsSyncPolicy.missingRemotely(local, document.settings)
                .filterKeys { it !in pending }
            applyRemote(SettingsSyncPolicy.remotePatch(local, document.settings, pending))
            saveSettings(PENDING, pending)
            saveSettings(BASELINE, localValues())
            val now = System.currentTimeMillis()
            lastSyncAtMs = now
            preferences.edit(commit = true) { putLong(SYNCED_AT, now) }
            mutableSyncedAt.value = now
            if (pending.isEmpty()) return
        }
    }

    private fun applyRemote(patch: SyncedSettings) {
        if (patch.isEmpty()) return
        var reconfigure = false
        SettingsSyncPolicy.routingPreset(patch[SettingsSyncPolicy.ROUTING_MODE])?.let {
            settings.setRoutingPreset(it)
            reconfigure = true
        }
        if (SHARE_FAVORITES) patch[SettingsSyncPolicy.FAVORITE_NODES]?.content?.let(settings::setFavoriteNodes)
        if (LIVE_ENABLED) patch[SettingsSyncPolicy.SHARE_PRESENCE]?.booleanOrNull?.let(settings::setSharePresence)
        patch[SettingsSyncPolicy.AUTOMATIC_SERVER]?.booleanOrNull?.let(settings::setAutomaticServer)
        patch[SettingsSyncPolicy.AUTO_RECONNECT]?.booleanOrNull?.let(settings::setAutoHealingEnabled)
        patch[SettingsSyncPolicy.KILL_SWITCH]?.booleanOrNull?.let(settings::setKillSwitchEnabled)
        patch[SettingsSyncPolicy.USE_DOH]?.booleanOrNull?.let {
            settings.setUseDoh(it)
            reconfigure = true
        }
        if (patch.keys.any(SettingsSyncPolicy.ANTI_DPI_KEYS::contains)) {
            val merged = localValues() + patch
            val enabled = merged.getValue(SettingsSyncPolicy.ANTI_DPI_ENABLED).boolean
            val packets = merged.getValue(SettingsSyncPolicy.ANTI_DPI_PACKETS).content
            val length = merged.getValue(SettingsSyncPolicy.ANTI_DPI_LENGTH).content
            val interval = merged.getValue(SettingsSyncPolicy.ANTI_DPI_INTERVAL).content
            settings.applySyncedAntiDpi(
                SettingsSyncPolicy.antiDpiPreset(enabled, packets, length, interval), packets, length, interval,
            )
            reconfigure = true
        }
        if (reconfigure && vpnController.state.value.state == VpnConnectionState.CONNECTED) vpnController.reconfigure()
        AppLogger.i(TAG, "Settings synced from the account: ${patch.keys.joinToString()}")
    }

    /**
     * The first exchange that includes this phone's favourites keeps those of
     * both sides: their union is applied here and returned for upload when it
     * differs. Before this phone has a list, the account's becomes its own.
     */
    private fun joinFavorites(local: SyncedSettings, remote: SyncedSettings): SyncedSettings? {
        if (!SHARE_FAVORITES) return null
        val key = SettingsSyncPolicy.FAVORITE_NODES
        val theirs = remote[key]?.content ?: return null
        val mine = local[key]?.content
        if (mine == null) {
            settings.setFavoriteNodes(theirs)
            return null
        }
        if (mine == theirs) return null
        val joined = FavoriteNodes.union(mine, theirs)
        settings.setFavoriteNodes(joined)
        return if (joined == theirs) null else mapOf(key to JsonPrimitive(joined))
    }

    private suspend fun reconcileFavorites(servers: List<TunnelServer>) = favoritesMutex.withLock {
        val stored = settings.favoriteNodes.value
        val state = FavoriteNodes.State(FavoriteNodes.parse(stored), settings.favoriteReconciledLocal())
        val local = settings.favoriteServerIds.value
        val result = FavoriteNodes.reconcile(state, local, FavoriteNodes.serversByNode(servers))
        val nodes = FavoriteNodes.canonical(result.state.nodes)
        if (nodes == stored && result.local == local && result.state.reconciledLocal == state.reconciledLocal) {
            return@withLock
        }
        settings.applyFavoriteReconcile(result.local, nodes, result.state.reconciledLocal)
    }

    /**
     * Keeps the live event stream open (docs/app-platform.md "Live events").
     * A silent connection ends by the read timeout; failures back off.
     */
    private suspend fun runEventStream() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var received = false
            try {
                val connection = repository.openEvents()
                try {
                    coroutineScope {
                        // A blocked read only ends when the connection closes.
                        val closer = launch {
                            try { awaitCancellation() } finally { connection.disconnect() }
                        }
                        withContext(Dispatchers.IO) {
                            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                                val parser = EventStreamParser()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.length > MAX_EVENT_LINE) break
                                    parser.line(line)?.let { event ->
                                        received = true
                                        onLiveEvent(event)
                                    }
                                }
                            }
                        }
                        closer.cancel()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: ApiException.Unauthorized) {
                // A regular request decides whether the session is gone and signs out.
                runCatching { repository.refreshAccount() }
                if (repository.session.value != SessionStatus.Authenticated) return
            } catch (error: Exception) {
                AppLogger.d(TAG, "Event stream ended: ${error.javaClass.simpleName}")
            }
            failures = if (received) 0 else failures + 1
            delay(PresencePolicy.retryDelayMs(failures, Random.nextDouble()))
        }
    }

    private fun onLiveEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.READY, LiveEvent.RESYNC -> scope.launch {
                refreshConfig()
                applyServiceRoutes()
                syncSettings()
                refreshAccountQuietly()
                refreshApprovals()
                refreshNotices()
            }
            LiveEvent.NOTIFICATIONS -> scope.launch { refreshNotices() }
            LiveEvent.APPROVALS -> scope.launch { refreshApprovals() }
            LiveEvent.SETTINGS -> scope.launch { syncSettings() }
            LiveEvent.CONFIG -> scope.launch { refreshConfig(); applyServiceRoutes() }
            LiveEvent.DEVICES -> scope.launch { refreshAccountQuietly() }
            LiveEvent.RULES -> scope.launch { applyAccountRules() }
            LiveEvent.SERVICE_ROUTES -> scope.launch { applyServiceRoutes() }
            LiveEvent.DROP -> drop?.refresh()
            LiveEvent.PRESENCE, LiveEvent.COMMANDS -> Unit
        }
        if (event == LiveEvent.READY || event == LiveEvent.RESYNC || event == LiveEvent.DEVICES) drop?.refresh()
        remote?.onEvent(event)
    }

    /**
     * The account's rules changed, here, in the cabinet or on another device.
     * The server folds them into the tunnel profile: fetch it and, while the
     * VPN runs, apply it when the routing it carries changed.
     */
    private suspend fun applyAccountRules() = rulesMutex.withLock {
        if (repository.session.value != SessionStatus.Authenticated) return@withLock
        val before = RuleRoutingKey.of(repository.tunnelProfile.value)
        try {
            val refresher = profileRefresher
            if (refresher != null) {
                refresher()
            } else {
                val subscriptionId = settings.selectedSubscriptionId.value
                    ?: repository.tunnelProfile.value?.subscriptionId
                    ?: return@withLock
                repository.prepareTunnel(subscriptionId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.d(TAG, "Rules refresh failed: ${error.javaClass.simpleName}")
            return@withLock
        }
        val after = RuleRoutingKey.of(repository.tunnelProfile.value)
        if (after != before && vpnController.state.value.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    /** A dedicated event never reloads or reconnects an active whitelist-bypass transport. */
    private suspend fun applyServiceRoutes() = rulesMutex.withLock {
        if (repository.session.value != SessionStatus.Authenticated) return@withLock
        val snapshot = vpnController.state.value
        if (snapshot.state != VpnConnectionState.CONNECTED) return@withLock
        val profile = repository.tunnelProfile.value ?: return@withLock
        val selected = profile.servers.firstOrNull { it.id == snapshot.serverId } ?: return@withLock
        if (selected.engine != com.leviknet.vpn.vpn.TunnelEngineKind.XRAY || selected.isMobileServer()) return@withLock
        try {
            val before = repository.activeServiceRoutes.value?.fingerprint
            val next = repository.prepareServiceRoutes(profile, selected, flagEnabled("service_routes"))
            val policy = next.serviceRoutes ?: return@withLock
            if (policy.fingerprint != before && (policy.rules.isNotEmpty() || before != null)) vpnController.reconfigure()
            else if (policy.fingerprint == before || policy.rules.isEmpty()) repository.acknowledgeUnchangedServiceRoutes(policy)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AppLogger.d(TAG, "Service route update remains pending")
        }
    }

    /**
     * Fetches the sign-ins waiting for approval. A new one posts a notification
     * while the app is not on the screen; on the screen it shows a dialog.
     */
    suspend fun refreshApprovals() = approvalsMutex.withLock {
        if (!LIVE_ENABLED || repository.session.value != SessionStatus.Authenticated) return@withLock
        val fresh = try {
            repository.pendingApprovals()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.d(TAG, "Pending approvals failed: ${error.javaClass.simpleName}")
            return@withLock
        }
        val known = mutableApprovals.value.map(PendingApproval::id).toSet()
        mutableApprovals.value = fresh
        val pending = fresh.map(PendingApproval::id).toSet()
        (known - pending).forEach(::cancelApprovalNotification)
        if (!foreground.value) fresh.filter { it.id !in known }.forEach(::postApprovalNotification)
        scheduleApprovalExpiry(fresh)
    }

    /** True when the sign-in was approved, false when it was declined. */
    suspend fun answerApproval(id: String, number: Int?): Boolean {
        val approval = mutableApprovals.value.firstOrNull { it.id == id }
        require(approval != null && LoginApproval.isAnswer(approval, number)) { "Unknown sign-in approval" }
        try {
            return repository.decideApproval(id, number)
        } finally {
            mutableApprovals.value = mutableApprovals.value.filterNot { it.id == id }
            cancelApprovalNotification(id)
            scope.launch { refreshApprovals() }
        }
    }

    /** Drops each request when it expires, even without another event. */
    private fun scheduleApprovalExpiry(approvals: List<PendingApproval>) {
        approvalsExpiry?.cancel()
        val next = approvals.minOfOrNull(PendingApproval::expiresAt) ?: return
        val waitMs = Duration.between(Instant.now(), next).toMillis().coerceAtLeast(0L) + APPROVAL_EXPIRY_SLACK_MS
        approvalsExpiry = scope.launch {
            delay(waitMs)
            refreshApprovals()
        }
    }

    /**
     * Takes the account's due notices, docs/app-platform.md "Notifications",
     * and shows each once. Taking them is what keeps Telegram from repeating them.
     */
    private suspend fun refreshNotices() = noticesMutex.withLock {
        if (repository.session.value != SessionStatus.Authenticated) return@withLock
        val notices = try {
            AccountNotices.parse(repository.takeNotifications()["notifications"])
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.d(TAG, "Notifications unavailable: ${error.javaClass.simpleName}")
            return@withLock
        }
        for (notice in notices) {
            if (!shownNotices.add(notice.id)) continue
            if (shownNotices.size > MAX_REMEMBERED) shownNotices.remove(shownNotices.first())
            postNotice(notice)
        }
    }

    private fun postNotice(notice: AccountNotice) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ACCOUNT_CHANNEL_ID,
                context.getString(R.string.notification_channel_account),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_account_desc) },
        )
        val notificationId = ACCOUNT_NOTIFICATION_ID_BASE + (notice.id.hashCode() and 0xff)
        val open = Intent(context, MainActivity::class.java).apply {
            action = AccountNotices.ACTION_OPEN
            putExtra(AccountNotices.EXTRA_TARGET, notice.target.name)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ACCOUNT_CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, AppIconManager(context).current()))
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { manager.notify(notificationId, notification) }
            .onFailure { AppLogger.w(TAG, "Could not show a notice") }
    }

    private fun forgetApprovals() {
        approvalsExpiry?.cancel()
        mutableApprovals.value.forEach { cancelApprovalNotification(it.id) }
        mutableApprovals.value = emptyList()
    }

    private fun approvalNotificationId(id: String) = APPROVAL_NOTIFICATION_ID_BASE + (id.hashCode() and 0xff)

    private fun postApprovalNotification(approval: PendingApproval) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                APPROVAL_CHANNEL_ID,
                context.getString(R.string.notification_channel_sign_in),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_sign_in_desc) },
        )
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notificationId = approvalNotificationId(approval.id)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(R.string.approval_notification_body, approval.platformLabel, approval.deviceName)
        val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, AppIconManager(context).current()))
            .setContentTitle(context.getString(R.string.approval_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setTimeoutAfter(Duration.between(Instant.now(), approval.expiresAt).toMillis().coerceAtLeast(1L))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { manager.notify(notificationId, notification) }
            .onFailure { AppLogger.w(TAG, "Could not show a sign-in approval") }
    }

    private fun cancelApprovalNotification(id: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(approvalNotificationId(id)) }
    }

    private suspend fun refreshAccountQuietly() {
        try {
            repository.refreshAccount()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.d(TAG, "Account refresh after an event failed: ${error.javaClass.simpleName}")
        }
    }

    /** Reports on every change of what the devices page shows, and every two minutes. */
    private suspend fun runPresence() {
        combine(vpnController.state, repository.tunnelProfile, settings.sharePresence) { snapshot, profile, share ->
            if (share) PresencePolicy.report(snapshot, profile?.servers.orEmpty()) else null
        }.distinctUntilChanged().collectLatest { report ->
            delay(PRESENCE_DEBOUNCE_MS)
            if (report == null) {
                withdrawPresence()
                return@collectLatest
            }
            while (true) {
                try {
                    sentPresence = if (repository.reportPresence(report)) report else null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLogger.d(TAG, "Presence report failed: ${error.javaClass.simpleName}")
                }
                delay(PresencePolicy.REFRESH_MS)
            }
        }
    }

    private fun forgetSyncState() {
        lastSyncAtMs = 0L
        preferences.edit(commit = true) {
            remove(PENDING)
            remove(BASELINE)
            remove(SYNCED_AT)
        }
        mutableSyncedAt.value = null
    }

    private fun localValues(): SyncedSettings = SettingsSyncPolicy.encode(
        LocalSyncedValues(
            routing = settings.routingPreset.value,
            automaticServer = settings.automaticServer.value,
            autoReconnect = settings.autoHealingEnabled.value,
            killSwitch = settings.killSwitchEnabled.value,
            useDoh = settings.useDoh.value,
            antiDpiEnabled = settings.antiDpiEnabled.value,
            antiDpiPackets = settings.antiDpiPackets.value,
            antiDpiLength = settings.antiDpiLength.value,
            antiDpiInterval = settings.antiDpiInterval.value,
            favoriteNodes = if (SHARE_FAVORITES) settings.favoriteNodes.value else null,
            sharePresence = if (LIVE_ENABLED) settings.sharePresence.value else null,
        ),
    )

    private fun loadSettings(key: String): SyncedSettings? = preferences.getString(key, null)?.let { raw ->
        runCatching { SettingsSyncPolicy.parseSettings(json.parseToJsonElement(raw)) }.getOrNull()
    }

    private fun saveSettings(key: String, value: SyncedSettings) {
        preferences.edit(commit = true) { putString(key, JsonObject(value).toString()) }
    }

    private companion object {
        const val TAG = "AppPlatform"
        const val PREFERENCES_NAME = "levik_app_platform"
        const val CONFIG_FILE = "remote-config.json"
        const val SYNC_ENABLED = "settings_sync_enabled"
        const val SYNCED_AT = "settings_synced_at"
        const val PENDING = "settings_sync_pending"
        const val BASELINE = "settings_sync_baseline"
        const val SALT = "rollout_salt"
        const val DISMISSED = "dismissed_announcements"
        const val NOTIFIED = "notified_announcements"
        const val CHANNEL_ID = "levik_announcements"
        const val NOTIFICATION_ID_BASE = 3000
        const val APPROVAL_CHANNEL_ID = "levik_sign_in"
        const val APPROVAL_NOTIFICATION_ID_BASE = 5000
        const val ACCOUNT_CHANNEL_ID = "levik_account"
        const val ACCOUNT_NOTIFICATION_ID_BASE = 5500
        const val APPROVAL_EXPIRY_SLACK_MS = 1_000L
        const val MAINTENANCE_NOTIFICATION_ID = 5300
        const val MAINTENANCE_CHECK_MS = 15_000L
        const val NODE_LOAD_MAX_AGE_MS = 2 * 60_000L
        const val NODE_LOAD_WAIT_MS = 2_500L
        const val MAX_REMEMBERED = 200
        const val MAX_EXCHANGES = 2
        const val DEFAULT_REFRESH_S = 15 * 60L
        const val SETTINGS_PUSH_DELAY_MS = 1_500L
        const val SETTINGS_SYNC_INTERVAL_MS = 30 * 60 * 1_000L
        val SALT_PATTERN = Regex("^[0-9a-f]{32}$")

        /** Play builds keep favourites on the phone; their data declaration does not cover them. */
        val SHARE_FAVORITES = !BuildConfig.IS_PLAY_DISTRIBUTION

        /** Live events and presence are part of the Direct build only. */
        val LIVE_ENABLED = !BuildConfig.IS_PLAY_DISTRIBUTION

        /** Moving off servers before their maintenance is part of the Direct build only. */
        val MAINTENANCE_ENABLED = !BuildConfig.IS_PLAY_DISTRIBUTION

        /** The remote is part of the Direct build only. */
        val REMOTE_ENABLED = !BuildConfig.IS_PLAY_DISTRIBUTION

        /** Levik Home is part of the Direct build only. */
        val HOME_ENABLED = !BuildConfig.IS_PLAY_DISTRIBUTION
        const val PRESENCE_DEBOUNCE_MS = 1_500L
        const val MAX_EVENT_LINE = 4_096
        val INACTIVE_TUNNEL = setOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR)
    }
}

/**
 * Anonymous requests to the website: no cookies, credentials or identifiers,
 * only the platform and version. Bound to the physical network when one is
 * given, so the server sees the user's operator rather than a Levik node.
 */
class RemoteConfigClient(private val version: String) {
    suspend fun fetch(network: Network?): Pair<JsonElement, RemoteConfig>? = withContext(Dispatchers.IO) {
        for (origin in ServiceEndpoints.current().apiOrigins) {
            val reply = runCatching { request(URL("$origin/api/app/v1/config"), network) }.getOrNull() ?: continue
            val (status, text) = reply
            // A host that does not serve the config yet: ask the next one.
            if (status == 404) continue
            // A rejected request would be rejected by the other domains too.
            if (status in 400..499) return@withContext null
            if (status != 200 || text == null) continue
            val response = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return@withContext null
            return@withContext RemoteConfigPolicy.parse(response)?.let { response to it }
        }
        null
    }

    /** "It's not the VPN": what the website knows about the caller's operator and Levik's servers. */
    suspend fun diagnose(network: Network?): DiagnoseReport? = withContext(Dispatchers.IO) {
        for (origin in ServiceEndpoints.current().apiOrigins) {
            val reply = runCatching { request(URL("$origin/api/app/v1/diagnose"), network) }.getOrNull() ?: continue
            val (status, text) = reply
            if (status == 404) continue
            if (status in 400..499) return@withContext null
            if (status != 200 || text == null) continue
            return@withContext runCatching { DIAGNOSE_JSON.decodeFromString<DiagnoseReport>(text) }.getOrNull()
        }
        null
    }

    private fun request(url: URL, network: Network?): Pair<Int, String?> {
        val connection = (network?.openConnection(url, Proxy.NO_PROXY) ?: url.openConnection(Proxy.NO_PROXY))
            as HttpURLConnection
        try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("X-Levik-Client", "android/$version")
            }
            val status = connection.responseCode
            if (status != 200) return status to null
            val text = connection.inputStream.use { input ->
                val buffer = ByteArray(MAX_RESPONSE_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > MAX_RESPONSE_BYTES) null else String(buffer, 0, size, Charsets.UTF_8)
            }
            return status to text
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        val DIAGNOSE_JSON = Json { ignoreUnknownKeys = true }
    }
}
