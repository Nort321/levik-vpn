package com.leviknet.vpn

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.data.AppSettings
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DistributionConsentInstrumentedTest {
    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "consent-test-${UUID.randomUUID()}-"
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
    }

    @Test
    fun legacySettingsDoNotBecomePlayConsent() {
        val context = isolatedContext()
        context.getSharedPreferences("levik_settings_v1", Context.MODE_PRIVATE).edit()
            .putBoolean("whitelist_map_enabled", true)
            .putBoolean("anonymous_telemetry_enabled", true)
            .putBoolean("auto_connect_untrusted_wifi", true)
            .commit()

        val settings = AppSettings(context)
        assertEquals(!BuildConfig.IS_PLAY_DISTRIBUTION, settings.whitelistMapEnabled.value)
        assertEquals(!BuildConfig.IS_PLAY_DISTRIBUTION, settings.anonymousTelemetryEnabled.value)
        assertEquals(!BuildConfig.IS_PLAY_DISTRIBUTION, settings.autoConnectUntrustedWifi.value)
        assertEquals(!BuildConfig.IS_PLAY_DISTRIBUTION, settings.hasInstalledAppsConsent())
    }

    @Test
    fun acceptanceAndWithdrawalSurviveRestart() {
        val context = isolatedContext()
        AppSettings(context).apply {
            setWhitelistMapEnabled(true)
            setAnonymousTelemetryEnabled(true)
            setAutoConnectUntrustedWifi(true)
            acceptInstalledAppsConsent()
        }
        AppSettings(context).apply {
            assertTrue(whitelistMapEnabled.value)
            assertTrue(anonymousTelemetryEnabled.value)
            assertTrue(autoConnectUntrustedWifi.value)
            assertTrue(hasInstalledAppsConsent())
            setWhitelistMapEnabled(false)
            setAnonymousTelemetryEnabled(false)
            setAutoConnectUntrustedWifi(false)
        }
        AppSettings(context).apply {
            assertFalse(whitelistMapEnabled.value)
            assertFalse(anonymousTelemetryEnabled.value)
            assertFalse(autoConnectUntrustedWifi.value)
        }
    }
}
