package com.leviknet.vpn.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

internal fun compactAppScale(shortSideDp: Float, isTelevision: Boolean): Float =
    if (isTelevision || !shortSideDp.isFinite() || shortSideDp <= 0f) 1f
    else (shortSideDp / 412f).coerceIn(0.85f, 1f)

/** Match the reference phone proportions without changing system font preferences. */
@Composable
fun LevikAppScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val isTelevision = LocalConfiguration.current.uiMode and
        Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = compactAppScale(minOf(maxWidth.value, maxHeight.value), isTelevision)
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * scale, density.fontScale),
            content = content,
        )
    }
}
