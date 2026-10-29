package com.leviknet.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leviknet.vpn.AppContainer
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.core.logger.AppLogger
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketException
import java.io.File
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opt-in: uses this test installation's already authorized account and real VPN. */
@RunWith(AndroidJUnit4::class)
class SmartAutoPhysicalInstrumentedTest {
    private suspend fun ready(): Pair<Context, AppContainer> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realProfileAuto") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as LevikVpnApplication
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val container = application.container
        withTimeout(30_000) { container.repository.tunnelProfile.filterNotNull().first() }
        assertTrue("Complete VPN disclosure in the test installation first", container.vpnController.hasDisclosureConsent())
        assertEquals("Grant this test installation VPN permission first", null, container.vpnController.permissionIntent())
        container.vpnController.disconnect()
        await(container, 8_000) { it.state == VpnConnectionState.DISCONNECTED }
        container.settings.setAutomaticServer(true)
        return context to container
    }

    private suspend fun await(container: AppContainer, timeout: Long = 45_000, predicate: (VpnSnapshot) -> Boolean): VpnSnapshot =
        try { withTimeout(timeout) { container.vpnController.state.first(predicate) } }
        catch (error: TimeoutCancellationException) {
            val state = container.vpnController.state.value
            File(ApplicationProvider.getApplicationContext<Context>().filesDir, "physical-auto-timeout-stacks.txt").writeText(
                Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                    thread.name + "\n" + stack.joinToString("\n")
                },
            )
            throw AssertionError("VPN wait expired: state=${state.state}, hotSwitch=${state.hotSwitch?.phase}, certain=${state.mainSelectionCertain}", error)
        }

    private suspend fun transfer(context: Context): Network {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // TUN establishment precedes ConnectivityManager's asynchronous onAvailable.
        // Android may keep its NetworkAgent during a full VpnService handover.
        val vpn = withTimeout(5_000) {
            var available: Network? = null
            while (available == null) {
                available = manager.allNetworks.firstOrNull {
                    val caps = manager.getNetworkCapabilities(it)
                    val owned = Build.VERSION.SDK_INT < 30 || caps?.ownerUid == Process.myUid() || caps?.ownerUid == -1
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true || !owned) false
                    else try {
                        // Netd's UID permissions may arrive after NetworkCapabilities.
                        // Check actual socket access before issuing the strict data proof.
                        it.socketFactory.createSocket().use { }
                        true
                    } catch (_: SocketException) { false }
                }
                if (available == null) delay(20)
            }
            available
        }
        val connection = vpn.openConnection(URL("https://speed.cloudflare.com/__down?bytes=65536")) as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            assertEquals(200, connection.responseCode)
            assertEquals(65_536, connection.inputStream.use { it.readBytes().size })
        } finally { connection.disconnect() }
        return vpn
    }

    @Test(timeout = 120_000) fun cancelDuringMeasurementsAndReconnect(): Unit = runBlocking {
        val (context, container) = ready()
        try {
            val started = Instant.now()
            container.vpnController.connect()
            withTimeout(15_000) {
                while (AppLogger.getLogs().none { it.tag == "SmartRoutePreflight" && it.message == "measurement begin" && it.timestamp >= started }) delay(20)
            }
            val cancelledAt = System.nanoTime()
            container.vpnController.disconnect()
            await(container, 5_000) { it.state == VpnConnectionState.DISCONNECTED }
            AppLogger.i("PhysicalAutoTest", "Auto measurement cancellation ms=${(System.nanoTime() - cancelledAt) / 1_000_000}")
            delay(1_000)
            assertEquals(VpnConnectionState.DISCONNECTED, container.vpnController.state.value.state)
            container.vpnController.connect()
            await(container) { it.state == VpnConnectionState.CONNECTED }
            transfer(context)
        } finally {
            container.vpnController.disconnect()
            await(container, 8_000) { it.state == VpnConnectionState.DISCONNECTED }
        }
    }

    @Test(timeout = 180_000) fun coldAutoManualSwitchesAndExplicitReconnect(): Unit = runBlocking {
        val (context, container) = ready()
        try {
            val started = System.nanoTime()
            container.vpnController.connect()
            var initial = await(container) { it.state == VpnConnectionState.CONNECTED }
            AppLogger.i("PhysicalAutoTest", "Cold Auto connection ms=${(System.nanoTime() - started) / 1_000_000}")
            AppLogger.i("PhysicalAutoTest", "Auto engine=${initial.engine}, country=${initial.serverCountryCode}, hot targets=${initial.hotSwitchServerIds.size}")
            var initialNetwork = transfer(context)
            var profile = requireNotNull(container.repository.tunnelProfile.value)
            container.settings.setAutomaticServer(false)
            if (profile.servers.none { it.id in initial.hotSwitchServerIds && it.countryCode != initial.serverCountryCode }) {
                // Auto can legitimately choose a native protocol or a profile that was
                // refreshed during probing. HotSwitch requires an installed ordinary Xray pool.
                val baseline = profile.servers.first {
                    it.engine == TunnelEngineKind.XRAY && it.isSmartAutoCandidate() && it.id != initial.serverId
                }
                container.vpnController.switchServer(baseline.id, forceReconnect = true)
                initial = await(container) { it.state == VpnConnectionState.CONNECTED && it.serverId == baseline.id && it.hotSwitchServerIds.isNotEmpty() }
                initialNetwork = transfer(context)
                profile = requireNotNull(container.repository.tunnelProfile.value)
                AppLogger.i("PhysicalAutoTest", "Compatible manual Xray baseline and data passed; hot targets=${initial.hotSwitchServerIds.size}")
            }
            val first = profile.servers.first { it.id in initial.hotSwitchServerIds && it.id != initial.serverId && it.countryCode != initial.serverCountryCode }
            AppLogger.i("PhysicalAutoTest", "First HotSwitch begin")
            container.vpnController.switchServer(first.id, hotSwitch = true)
            await(container) { it.state == VpnConnectionState.CONNECTED && it.serverId == first.id && it.hotSwitch?.phase == HotSwitchPhase.APPLIED }
            assertTrue(container.vpnController.state.value.mainSelectionCertain)
            assertEquals("HotSwitch replaced the system VPN network", initialNetwork, transfer(context))
            AppLogger.i("PhysicalAutoTest", "First HotSwitch and data passed")
            val second = profile.servers.first { it.id in initial.hotSwitchServerIds && it.id != first.id && it.countryCode != first.countryCode }
            container.vpnController.switchServer(second.id, hotSwitch = true)
            await(container) { it.state == VpnConnectionState.CONNECTED && it.serverId == second.id && it.hotSwitch?.phase == HotSwitchPhase.APPLIED }
            assertEquals("HotSwitch replaced the system VPN network", initialNetwork, transfer(context))
            AppLogger.i("PhysicalAutoTest", "Second HotSwitch and data passed")
            val reconnectStarted = Instant.now()
            container.vpnController.switchServer(first.id, forceReconnect = true)
            await(container) { it.state == VpnConnectionState.CONNECTED && it.serverId == first.id }
            assertTrue("Explicit reconnect did not start a fresh VPN session", AppLogger.getLogs().any {
                it.tag == "LevikVpnService" && it.timestamp >= reconnectStarted && it.message == "Establishing VPN connection"
            })
            val reconnectedNetwork = transfer(context)
            AppLogger.i("PhysicalAutoTest", "Explicit reconnect and data passed")
            container.settings.setAutomaticServer(true)
            container.vpnController.refreshAutomaticSelection()
            await(container) { it.state == VpnConnectionState.CONNECTED && it.mainSelectionCertain && it.hotSwitch?.phase == HotSwitchPhase.APPLIED }
            assertEquals("Live Auto replaced the system VPN network", reconnectedNetwork, transfer(context))
            AppLogger.i("PhysicalAutoTest", "Return to Auto and data passed")
        } finally {
            container.vpnController.disconnect()
            await(container, 8_000) { it.state == VpnConnectionState.DISCONNECTED }
        }
    }
}
