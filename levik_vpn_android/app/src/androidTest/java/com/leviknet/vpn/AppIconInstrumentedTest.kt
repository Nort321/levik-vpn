package com.leviknet.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.data.AppIcon
import com.leviknet.vpn.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIconInstrumentedTest {
    @Test
    fun switchingIconsKeepsOneLauncherAndPersistsWithoutDisablingMainActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
                    assertEquals(MainActivity::class.java.name, launchers.single().activityInfo.targetActivity)
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
}
