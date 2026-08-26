package com.leviknet.vpn

import android.app.Application
import com.leviknet.vpn.core.network.AppAttestationPolicy
import com.leviknet.vpn.core.network.CensorshipRadarWorker
import com.leviknet.vpn.core.network.MobileApiClient
import com.leviknet.vpn.core.network.RequestSigner
import com.leviknet.vpn.core.network.createAppAttestationProvider
import com.leviknet.vpn.core.security.DeviceIdentity
import com.leviknet.vpn.core.security.HybridProfileDecryptor
import com.leviknet.vpn.core.security.SecureFileStore
import com.leviknet.vpn.core.security.TrialDeviceBinding
import com.leviknet.vpn.core.update.createAppUpdateManager
import com.leviknet.vpn.data.AppRepository
import com.leviknet.vpn.data.AppSettings
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.data.TrafficHistoryStore
import com.leviknet.vpn.data.isActiveAt
import com.leviknet.vpn.vpn.RussianRoutingData
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnController
import com.leviknet.vpn.vpn.WifiAutoConnectMonitor
import com.leviknet.vpn.vpn.XrayRuntime
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AppContainer(application: Application) {
    val appContext: android.content.Context = application.applicationContext
    val nativeCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    val deviceIdentity = DeviceIdentity()
    private val trialDeviceBinding = TrialDeviceBinding(application)
    val secureStore = SecureFileStore(application)
    val settings = AppSettings(application)
    val russianRoutingData = RussianRoutingData(application)
    val xrayRuntime = XrayRuntime(json)

    private val requestSigner = RequestSigner(deviceIdentity)
    private val attestationProvider = createAppAttestationProvider(
        application,
        BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER
            .takeIf { it > 0L },
    )
    val apiClient = MobileApiClient(
        baseUrl = BuildConfig.CABINET_BASE_URL,
        signer = requestSigner,
        attestationProvider = attestationProvider,
        requireAttestation = AppAttestationPolicy.requiresIntegrity(
            playIntegrityEnabled = BuildConfig.PLAY_INTEGRITY_ENABLED,
            isDebugBuild = BuildConfig.DEBUG,
        ),
        json = json,
    )
    val updateManager = createAppUpdateManager(application)
    private val profileDecryptor = HybridProfileDecryptor(deviceIdentity)

    val repository = AppRepository(
        apiClient = apiClient,
        deviceIdentity = deviceIdentity,
        trialDeviceBinding = trialDeviceBinding,
        secureStore = secureStore,
        profileDecryptor = profileDecryptor,
        xrayRuntime = xrayRuntime,
        json = json,
    )

    val trafficHistoryStore = TrafficHistoryStore(application, json, nativeCleanupScope)
    val vpnController = VpnController(application, secureStore)

    private val wifiAutoConnectMonitor = WifiAutoConnectMonitor(
        context = application,
        settings = settings,
        vpnController = vpnController,
        scope = nativeCleanupScope,
    )

    init {
        wifiAutoConnectMonitor.start()
        CensorshipRadarWorker.configure(
            application,
            settings.anonymousTelemetryEnabled.value,
        )
        nativeCleanupScope.launch {
            while (true) {
                delay(SUBSCRIPTION_REFRESH_INTERVAL_MS)
                if (repository.session.value != SessionStatus.Authenticated) continue
                runCatching {
                    val previousSubscriptionId = repository.cachedTunnel()?.subscriptionId
                    val account = repository.refreshAccount()
                    val active = account.subscriptions.filter { it.isActiveAt(Instant.now()) }
                    val selected = active.firstOrNull {
                        it.uuid == settings.selectedSubscriptionId.value
                    } ?: active.firstOrNull()
                    if (selected == null) {
                        vpnController.disconnect()
                    } else {
                        settings.setSelectedSubscriptionId(selected.uuid)
                        val profile = repository.prepareTunnel(selected.uuid)
                        if (previousSubscriptionId != null &&
                            previousSubscriptionId != selected.uuid &&
                            vpnController.state.value.state !in setOf(
                                VpnConnectionState.DISCONNECTED,
                                VpnConnectionState.ERROR,
                            )
                        ) {
                            vpnController.disconnect()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 60 * 60 * 1000L
    }
}
