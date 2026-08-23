package com.leviknet.vpn

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.widget.LevikVpnWidgetActionReceiver
import com.leviknet.vpn.widget.LevikVpnWidgetProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DistributionManifestInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageManager = context.packageManager

    @Test
    fun distributionPolicyMatchesSelectedFlavor() {
        if (BuildConfig.IS_PLAY_DISTRIBUTION) {
            assertFalse(BuildConfig.SELF_UPDATE_ENABLED)
            assertFalse(BuildConfig.EXTERNAL_PURCHASES_ENABLED)
            assertTrue(BuildConfig.PLAY_INTEGRITY_ENABLED)
        } else {
            assertTrue(BuildConfig.SELF_UPDATE_ENABLED)
            assertTrue(BuildConfig.EXTERNAL_PURCHASES_ENABLED)
            assertFalse(BuildConfig.PLAY_INTEGRITY_ENABLED)
        }
    }

    @Test
    fun installPermissionAndFileProviderExistOnlyInDirectDistribution() {
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requestedPermissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        val fileProvider = packageManager.resolveContentProvider(
            "${context.packageName}.fileprovider",
            PackageManager.GET_META_DATA,
        )

        if (BuildConfig.IS_PLAY_DISTRIBUTION) {
            assertFalse(requestedPermissions.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
            assertNull(fileProvider)
        } else {
            assertTrue(requestedPermissions.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
            assertNotNull(fileProvider)
            assertFalse(requireNotNull(fileProvider).exported)
        }
    }

    @Test
    fun activationAppLinkIsExactAndLegacyCustomSchemeDoesNotResolve() {
        assertTrue(resolvesToMainActivity("https://leviknet.com/activate?code=Abc_1234-xyz"))
        assertFalse(resolvesToMainActivity("https://leviknet.com/app?code=Abc_1234-xyz"))
        assertFalse(resolvesToMainActivity("levikvpn://auth?token=secret-value"))
    }

    @Test
    fun widgetToggleReceiverIsNotExported() {
        val actionReceiver = packageManager.getReceiverInfo(
            ComponentName(context, LevikVpnWidgetActionReceiver::class.java),
            0,
        )
        val widgetProvider = packageManager.getReceiverInfo(
            ComponentName(context, LevikVpnWidgetProvider::class.java),
            0,
        )

        assertFalse(actionReceiver.exported)
        assertTrue(widgetProvider.exported)
    }

    private fun resolvesToMainActivity(rawUri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, rawUri.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(context.packageName)
        }
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { resolveInfo -> resolveInfo.activityInfo.name == MainActivity::class.java.name }
    }
}
