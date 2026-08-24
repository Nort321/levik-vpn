package com.leviknet.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.leviknet.vpn.core.security.SecureFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class VpnController(
    private val context: Context,
    private val secureStore: SecureFileStore,
) {
    val state: StateFlow<VpnSnapshot> = VpnStateStore.state

    fun permissionIntent(): Intent? = VpnService.prepare(context)

    suspend fun hasDisclosureConsent(): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            secureStore.get(SecureFileStore.VPN_DISCLOSURE_CONSENT)
        }.getOrNull() ?: return@withContext false
        try {
            bytes.contentEquals(CONSENT_VALUE)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun acceptDisclosure() = withContext(Dispatchers.IO) {
        secureStore.put(SecureFileStore.VPN_DISCLOSURE_CONSENT, CONSENT_VALUE)
    }

    suspend fun connect() {
        check(hasDisclosureConsent()) { "VPN disclosure consent is required" }
        ContextCompat.startForegroundService(
            context,
            Intent(context, LevikVpnService::class.java).setAction(LevikVpnService.ACTION_CONNECT),
        )
    }

    fun disconnect() {
        context.startService(
            Intent(context, LevikVpnService::class.java).setAction(LevikVpnService.ACTION_DISCONNECT),
        )
    }

    fun reconfigure() {
        context.startService(
            Intent(context, LevikVpnService::class.java)
                .setAction(LevikVpnService.ACTION_RECONFIGURE),
        )
    }

    fun switchServer(serverId: String) {
        context.startService(
            Intent(context, LevikVpnService::class.java)
                .setAction(LevikVpnService.ACTION_SWITCH_SERVER)
                .putExtra(LevikVpnService.EXTRA_SERVER_ID, serverId),
        )
    }

    fun pause(durationMinutes: Int) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LevikVpnService::class.java)
                .setAction(LevikVpnService.ACTION_PAUSE)
                .putExtra(LevikVpnService.EXTRA_PAUSE_MINUTES, durationMinutes),
        )
    }

    fun resume() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LevikVpnService::class.java)
                .setAction(LevikVpnService.ACTION_RESUME),
        )
    }

    companion object {
        private val CONSENT_VALUE = "accepted-v1".encodeToByteArray()
    }
}
