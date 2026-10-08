package com.leviknet.vpn.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in provider check: supply a disposable public editing link as an instrumentation argument. */
@RunWith(AndroidJUnit4::class)
class YandexGuestRefreshDeviceTest {
    @Test(timeout = 60_000) fun anonymousRefreshThroughPrivateGuestService() = runBlocking {
        val document = InstrumentationRegistry.getArguments().getString("yandexDocumentUrl")
        assumeTrue("A public test document must be supplied explicitly", document != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
        assertNotNull("A validated underlying network is required", network)
        val auth = refreshYandexGuest(context, requireNotNull(document), requireNotNull(network))
        assertNotNull("Anonymous guest refresh did not complete", auth)
        YandexContract.validateProviderAuth(requireNotNull(auth))
    }
}
