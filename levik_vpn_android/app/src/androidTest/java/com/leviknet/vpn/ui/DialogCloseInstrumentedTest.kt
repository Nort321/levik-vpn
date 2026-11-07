package com.leviknet.vpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.R
import com.leviknet.vpn.core.sharing.SharingSnapshot
import com.leviknet.vpn.ui.sharing.SharingDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DialogCloseInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun logsCloseStaysBelowAllActionsAtTheRight() {
        var dismissals = 0
        var labels = emptyList<String>()
        showWithLargeText {
            labels = listOf(
                stringResource(R.string.close),
                stringResource(R.string.logs_clear_btn),
                stringResource(R.string.logs_share_btn),
                stringResource(R.string.logs_send_support),
            )
            LogsViewerDialog(emptyList(), "", {}, {}, {}, onDismiss = { dismissals++ })
        }

        assertCloseAtBottomRight(labels.first(), labels.drop(1))
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun sharingCloseStaysBelowItsScrollableContent() {
        var dismissals = 0
        var closeLabel = ""
        var title = ""
        showWithLargeText {
            closeLabel = stringResource(R.string.sharing_close)
            title = stringResource(R.string.sharing_title)
            SharingDialog(SharingSnapshot(), ownerConnected = false, incomingInvitation = null,
                onReceive = {}, onScan = {}, onDismiss = { dismissals++ })
        }

        assertCloseAtBottomRight(closeLabel, listOf(title))
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    private fun showWithLargeText(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                MaterialTheme(content = content)
            }
        }
        compose.waitForIdle()
    }

    private fun assertCloseAtBottomRight(label: String, above: List<String>) {
        val close = compose.onNodeWithText(label).assertIsDisplayed()
        val bounds = close.fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val maxInset = with(compose.density) { 36.dp.toPx() }
        assertTrue("Close should be at the right edge", dialog.right - bounds.right <= maxInset)
        assertTrue("Close should be at the bottom edge", dialog.bottom - bounds.bottom <= maxInset)
        for (other in above) {
            val otherBounds = compose.onNodeWithText(other).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Close should be below '$other'", bounds.top >= otherBounds.bottom)
        }
        close.performClick()
    }
}
