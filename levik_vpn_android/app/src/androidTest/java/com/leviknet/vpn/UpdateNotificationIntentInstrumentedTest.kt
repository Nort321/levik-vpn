package com.leviknet.vpn

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.core.update.consumeUpdateNotificationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateNotificationIntentInstrumentedTest {
    @Test
    fun updateNotificationIsConsumedOnceOnlyInDirect() {
        val intent = Intent("com.leviknet.vpn.action.OPEN_UPDATE")
        assertEquals(BuildConfig.SELF_UPDATE_ENABLED, consumeUpdateNotificationIntent(intent))
        assertFalse(consumeUpdateNotificationIntent(intent))
    }

    @Test
    fun ordinaryLaunchAndActivationLinksArePreserved() {
        val launch = Intent(Intent.ACTION_MAIN)
        assertFalse(consumeUpdateNotificationIntent(launch))
        assertEquals(Intent.ACTION_MAIN, launch.action)

        val uri = "https://leviknet.com/activate?code=test".toUri()
        val activation = Intent(Intent.ACTION_VIEW, uri)
        assertFalse(consumeUpdateNotificationIntent(activation))
        assertEquals(Intent.ACTION_VIEW, activation.action)
        assertEquals(uri, activation.data)
    }
}
