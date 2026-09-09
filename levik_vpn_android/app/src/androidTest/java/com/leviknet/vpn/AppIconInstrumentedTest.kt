package com.leviknet.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.core.notification.AppIconArtwork
import com.leviknet.vpn.data.AppIcon
import com.leviknet.vpn.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIconInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aliasesTargetTransientLauncherInsteadOfTheRunningActivity() {
        AppIcon.entries.forEach { icon ->
            val info = context.packageManager.getActivityInfo(
                ComponentName(context.packageName, "com.leviknet.vpn.${icon.aliasName}"),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals(LauncherActivity::class.java.name, info.targetActivity)
            assertEquals(icon.launcherResource, info.icon)
        }
    }

    @Test
    fun switchingIconsKeepsOneLauncherAndPersistsWithoutDisablingMainActivity() {
        val settings = AppSettings(context)
        val original = settings.appIcon.value
        val manager = context.packageManager
        try {
            // Include returning to Light and a repeated selection.
            (AppIcon.entries + AppIcon.LIGHT + AppIcon.LIGHT).forEach { icon ->
                settings.setAppIcon(icon)
                assertEquals(icon, settings.appIcon.value)
                assertEquals(icon, AppSettings(context).appIcon.value)
                listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { category ->
                    val launchers = manager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName), 0,
                    )
                    assertEquals(1, launchers.size)
                    assertEquals("com.leviknet.vpn.${icon.aliasName}", launchers.single().activityInfo.name)
                    assertEquals(LauncherActivity::class.java.name, launchers.single().activityInfo.targetActivity)
                    assertTrue(launchers.single().activityInfo.icon != 0)
                }
                val mainState = manager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java))
                assertTrue(mainState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    mainState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            }
        } finally {
            settings.setAppIcon(original)
        }
    }

    @Test
    fun notificationArtworkUsesTheNewLogoWithTransparentStatusBarBackground() {
        AppIcon.entries.forEach { icon ->
            val bitmap = AppIconArtwork.smallIcon(context, icon).bitmap
            assertNotNull(bitmap)
            requireNotNull(bitmap)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val visible = pixels.count { it ushr 24 > 0 }
            assertTrue(visible > pixels.size / 4)
            assertTrue(visible < pixels.size * 9 / 10)
        }
        assertFalse(AppIconArtwork.largeIcon(context, AppIcon.LIGHT).sameAs(AppIconArtwork.largeIcon(context, AppIcon.DARK)))
        assertFalse(AppIconArtwork.largeIcon(context, AppIcon.LIGHT).sameAs(AppIconArtwork.largeIcon(context, AppIcon.MONOCHROME)))
    }

    @Test
    fun updatingArtworkPreservesTheExistingVpnNotificationStateAndActions() {
        val intent = PendingIntent.getActivity(
            context, 271, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val original = Notification.Builder(context, "icon-test")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("VPN")
            .setContentText("Paused 05:00")
            .setContentIntent(intent)
            .setOngoing(true)
            .setWhen(123456L)
            .addAction(Notification.Action.Builder(null, "Resume", intent).build())
            .build()
        val updated = AppIconArtwork.updateNotification(context, original, AppIcon.DARK)
        assertEquals(original.channelId, updated.channelId)
        assertEquals(original.`when`, updated.`when`)
        assertEquals("Paused 05:00", updated.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(intent, updated.contentIntent)
        assertEquals("Resume", updated.actions.single().title)
        assertTrue(updated.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(updated.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNotNull(updated.smallIcon)
        assertNotNull(updated.getLargeIcon())
    }
}
