package com.leviknet.vpn.vpn

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnManifestInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun vpnServiceRequiresSystemBindingPermissionAndOptsOutOfAlwaysOn() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, LevikVpnService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertTrue(service.exported)
        assertEquals(Manifest.permission.BIND_VPN_SERVICE, service.permission)
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertTrue(service.metaData.containsKey(ALWAYS_ON_METADATA))
        assertFalse(service.metaData.getBoolean(ALWAYS_ON_METADATA))
    }

    private companion object {
        const val ALWAYS_ON_METADATA = "android.net.VpnService.SUPPORTS_ALWAYS_ON"
    }
}
