package com.leviknet.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in acceptance check with an already running Levik VPN; never starts or replaces its core/TUN. */
@RunWith(AndroidJUnit4::class)
class SmartAutoLoopbackInstrumentedTest {
    @Test fun protectedPhysicalNetworkSocketStillReachesLocalTuicProxy() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smartAutoLoopbackWithVpn") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assumeTrue(manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true })
        val physical = manager.allNetworks.firstOrNull { network -> manager.getNetworkCapabilities(network)?.let {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true }
        assumeTrue(physical != null)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 3_000
            Socket().use { client ->
                assertTrue("The VPN owner could not protect its own local socket", VpnService().protect(client))
                requireNotNull(physical).bindSocket(client)
                client.soTimeout = 3_000
                client.connect(InetSocketAddress("127.0.0.1", listener.localPort), 3_000)
                client.getOutputStream().write(42)
                listener.accept().use { accepted ->
                    accepted.soTimeout = 3_000
                    assertEquals(42, accepted.getInputStream().read())
                    accepted.getOutputStream().write(24)
                }
                assertEquals(24, client.getInputStream().read())
            }
        }
    }
}
