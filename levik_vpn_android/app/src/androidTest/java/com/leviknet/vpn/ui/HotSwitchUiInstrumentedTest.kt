package com.leviknet.vpn.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.R
import com.leviknet.vpn.data.ThemeMode
import com.leviknet.vpn.ui.theme.LevikTheme
import com.leviknet.vpn.vpn.HotSwitchPhase
import com.leviknet.vpn.vpn.HotSwitchSnapshot
import com.leviknet.vpn.vpn.VpnConnectionState
import com.leviknet.vpn.vpn.VpnSnapshot
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Compose surface only: never launches MainActivity, an account, or VpnService. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HotSwitchUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noticesExposeOnlyConfirmedOutcomesAndExplicitReconnect() {
        val context = localizedContext()
        val state = mutableStateOf(snapshot())
        val reconnects = AtomicInteger()
        show(state, context, width = 360, reconnects = reconnects)
        compose.onNodeWithText(context.getString(R.string.hot_switch_reconnect_all)).assertDoesNotExist()
        compose.runOnIdle { state.value = snapshot(HotSwitchPhase.PREPARING) }
        compose.onNodeWithText(context.getString(R.string.hot_switch_preparing_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_preparing_desc, TARGET)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_reconnect_all)).assertDoesNotExist()
        capture("preparing-360")
        compose.runOnIdle { state.value = snapshot(HotSwitchPhase.PREPARING).copy(mainSelectionCertain = false) }
        compose.onNodeWithText(context.getString(R.string.hot_switch_preparing_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_preparing_unknown_desc)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_uncertain_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hot_switch_reconnect_all)).assertDoesNotExist()
        capture("preparing-unconfirmed-360")

        val titles = mapOf(HotSwitchPhase.APPLIED to R.string.hot_switch_applied_title,
            HotSwitchPhase.FAILED to R.string.hot_switch_failed_title,
            HotSwitchPhase.UNCERTAIN to R.string.hot_switch_uncertain_title)
        for ((index, phase) in titles.keys.withIndex()) {
            compose.runOnIdle { state.value = snapshot(phase) }
            compose.onNodeWithText(context.getString(titles.getValue(phase))).assertIsDisplayed()
            val action = compose.onNodeWithText(context.getString(R.string.hot_switch_reconnect_all))
            action.assertIsDisplayed().assertIsEnabled()
            capture("${phase.name.lowercase(Locale.ROOT)}-360")
            action.performClick()
            compose.runOnIdle { assertEquals(index + 1, reconnects.get()) }
        }
        compose.runOnIdle {
            val applied = snapshot(HotSwitchPhase.APPLIED)
            state.value = applied.copy(hotSwitch = applied.hotSwitch?.copy(preferenceSaved = false))
        }
        compose.onNodeWithText(context.getString(R.string.hot_switch_applied_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_preference_not_saved)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_failed_title)).assertDoesNotExist()
        capture("applied-not-saved-360")
    }

    @Test fun unknownMainCannotDisplayAStaleSuccessAndFitsNarrowLargeText() {
        val context = localizedContext(fontScale = 1.3f)
        val state = mutableStateOf(snapshot(HotSwitchPhase.APPLIED).copy(mainSelectionCertain = false))
        val reconnects = AtomicInteger()
        show(state, context, width = 240, reconnects = reconnects)
        compose.onNodeWithText(context.getString(R.string.hot_switch_uncertain_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_uncertain_desc)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hot_switch_applied_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hot_switch_failed_desc)).assertDoesNotExist()
        val action = compose.onNodeWithText(context.getString(R.string.hot_switch_reconnect_all))
        action.assertIsDisplayed().assertIsEnabled()
        capture("uncertain-240-large-text")
        action.performClick()
        compose.runOnIdle { assertEquals(1, reconnects.get()) }

        // A missing transient notice must not turn uncertainty back into success.
        compose.runOnIdle { state.value = snapshot().copy(mainSelectionCertain = false) }
        compose.onNodeWithText(context.getString(R.string.hot_switch_uncertain_title)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(state = VpnConnectionState.DISCONNECTED) }
        compose.onNodeWithText(context.getString(R.string.hot_switch_uncertain_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hot_switch_reconnect_all)).assertDoesNotExist()
    }

    private fun show(state: MutableState<VpnSnapshot>, context: Context, width: Int, reconnects: AtomicInteger) {
        compose.setContent {
            val density = Density(LocalDensity.current.density, context.resources.configuration.fontScale)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalDensity provides density) {
                LevikTheme(ThemeMode.DARK) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(ROOT)) {
                        HotSwitchNotice(state.value, { reconnects.incrementAndGet() }, Modifier.width(width.dp).padding(12.dp))
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "hot-switch-ui").apply { mkdirs() }
        val image = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun localizedContext(fontScale: Float = 1f): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Use the isolated HotSwitch test runner", Application::class.java, context.javaClass)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru")); this.fontScale = fontScale
        }
        return context.createConfigurationContext(configuration)
    }

    private fun snapshot(phase: HotSwitchPhase? = null) = VpnSnapshot(state = VpnConnectionState.CONNECTED,
        serverId = "old", serverName = "Германия", serverCountryCode = "DE", connectedDurationSeconds = 300,
        hotSwitch = phase?.let { HotSwitchSnapshot(it, "new", TARGET) })

    private companion object {
        const val ROOT = "hot-switch-synthetic-ui"
        const val TARGET = "Нидерланды · Амстердам"
    }
}
