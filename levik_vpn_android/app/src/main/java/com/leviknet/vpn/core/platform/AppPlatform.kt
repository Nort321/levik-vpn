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
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.notification.AppIconArtwork
import com.leviknet.vpn.data.AppIconManager
import com.leviknet.vpn.data.AppRepository
import com.leviknet.vpn.data.AppSettings
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnController
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val configFile = File(context.noBackupFilesDir, CONFIG_FILE)
    private val configClient = RemoteConfigClient(BuildConfig.VERSION_NAME)
    private val configMutex = Mutex()
    private val syncMutex = Mutex()

    @Volatile
    private var stored: StoredRemoteConfig? = null

    @Volatile
    private var lastSyncAtMs = 0L

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
            refreshConfigIfStale()
            while (true) {
                delay((stored?.config?.refreshAfterSeconds ?: DEFAULT_REFRESH_S) * 1_000L)
                refreshConfig()
            }
        }
        scope.launch {
            repository.session.collect { status ->
                when (status) {
                    SessionStatus.Authenticated -> syncSettings()
                    SessionStatus.SignedOut -> forgetSyncState()
                    SessionStatus.Loading -> Unit
                }
            }
        }
        scope.launch {
            // Local edits are sent shortly after the user stops changing them.
            combine(
                listOf<Flow<Any>>(
                    settings.routingPreset, settings.automaticServer, settings.autoHealingEnabled,
                    settings.killSwitchEnabled, settings.useDoh, settings.antiDpiEnabled,
                    settings.antiDpiPackets, settings.antiDpiLength, settings.antiDpiInterval,
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
        scope.launch {
            refreshConfigIfStale()
            if (System.currentTimeMillis() - lastSyncAtMs >= SETTINGS_SYNC_INTERVAL_MS) syncSettings()
        }
    }

    fun dismissAnnouncement(id: String) {
        val dismissed = rememberedIds(preferences.getStringSet(DISMISSED, emptySet()).orEmpty(), listOf(id))
        preferences.edit(commit = true) { putStringSet(DISMISSED, dismissed) }
        publishAnnouncements()
    }

    fun flagEnabled(key: String): Boolean = RemoteConfigPolicy.flagEnabled(stored?.config, key, salt)

    fun protocolAdvice(): ProtocolAdvice? = RemoteConfigPolicy.currentAdvice(stored, System.currentTimeMillis())

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
        val notificationId = NOTIFICATION_ID_BASE + (announcement.id.hashCode() and 0x3ff)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, AppIconManager(context).current()))
            .setContentTitle(announcement.title)
            .setContentText(announcement.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(announcement.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { manager.notify(notificationId, notification) }
            .onFailure { AppLogger.w(TAG, "Could not show an announcement") }
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
            val local = localValues()
            pending = SettingsSyncPolicy.withoutConfirmed(pending, sent) + SettingsSyncPolicy.changes(current, local)
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
        const val MAX_REMEMBERED = 200
        const val MAX_EXCHANGES = 2
        const val DEFAULT_REFRESH_S = 15 * 60L
        const val SETTINGS_PUSH_DELAY_MS = 1_500L
        const val SETTINGS_SYNC_INTERVAL_MS = 30 * 60 * 1_000L
        val SALT_PATTERN = Regex("^[0-9a-f]{32}$")
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
        for (origin in ORIGINS) {
            val reply = runCatching { request(URL("$origin/api/app/v1/config"), network) }.getOrNull() ?: continue
            val (status, text) = reply
            // A rejected request would be rejected by the other domain too.
            if (status in 400..499) return@withContext null
            if (status != 200 || text == null) continue
            val response = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return@withContext null
            return@withContext RemoteConfigPolicy.parse(response)?.let { response to it }
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
        val ORIGINS = listOf("https://leviknet.org", "https://leviknet.com")
        const val TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
