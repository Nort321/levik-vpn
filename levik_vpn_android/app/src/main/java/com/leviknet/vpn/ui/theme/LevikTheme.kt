package com.leviknet.vpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.leviknet.vpn.data.ThemeMode

val LevikBlue = Color(0xFF2563EB)
val LevikDarkBlue = Color(0xFF1D4ED8)
val LevikBrightBlue = Color(0xFF0284C7)
val LevikCyan = Color(0xFF06B6D4)
val LevikGreen = Color(0xFF16A34A)
val LevikGreenRingFill = Color(0x2422C55E)
val LevikGreenRingBorder = Color(0xFF22C55E)
val LevikPurple = Color(0xFF6366F1)
val LevikPurpleContainer = Color(0xFF23204E)
val LevikTextPrimary = Color(0xFF0F172A)
val LevikTextSecondary = Color(0xFF475569)

// Reference UI specific colors
val LevikDarkBg = Color(0xFF030817)
val LevikDarkSurface = Color(0xFF081120)
val LevikDarkSurfaceCard = Color(0xFF0C1627)
val LevikDarkBorder = Color(0xFF263650)
val LevikDarkBadgeBg = Color(0xFF0D192C)
val LevikDarkMutedText = Color(0xFF9AA9C2)
val LevikLightBg = Color(0xFFF8FAFC)
val LevikLightSurface = Color(0xFFFFFFFF)
val LevikLightSurfaceCard = Color(0xFFF1F5F9)

object LevikDimensions {
    val ScreenHorizontalPadding = 20.dp
    val ScreenVerticalPadding = 18.dp
    val CardRadius = 22.dp
    val ControlRadius = 16.dp
    val ButtonHeight = 52.dp
    val IconButtonSize = 48.dp
    val SectionSpacing = 16.dp
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF6FF),
    onPrimaryContainer = Color(0xFF1D4ED8),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF2FF),
    onSecondaryContainer = Color(0xFF3730A3),
    tertiary = Color(0xFF16A34A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF14532D),
    background = LevikLightBg,
    onBackground = Color(0xFF0F172A),
    surface = LevikLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LevikLightSurfaceCard,
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF111827),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF93C5FD),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFF94A3B8),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    surfaceDim = Color(0xFFF1F5F9),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F5F9),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    scrim = Color.Black,
)

private val LevikTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.35).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.15.sp,
    ),
)

private val LevikShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

private val DarkColors = darkColorScheme(
    primary = LevikBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D3258),
    onPrimaryContainer = Color(0xFFBFDBFE),
    secondary = Color(0xFF818CF8),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF2E2A6E),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF032A17),
    tertiaryContainer = Color(0xFF0B3D28),
    onTertiaryContainer = Color(0xFF8FF0B8),
    background = LevikDarkBg,
    onBackground = Color(0xFFF7FAFF),
    surface = LevikDarkSurface,
    onSurface = Color(0xFFF7FAFF),
    surfaceVariant = LevikDarkSurfaceCard,
    onSurfaceVariant = LevikDarkMutedText,
    surfaceTint = LevikBlue,
    inverseSurface = Color(0xFFEAF0FA),
    inverseOnSurface = Color(0xFF0A1220),
    inversePrimary = LevikDarkBlue,
    outline = LevikDarkBorder,
    outlineVariant = Color(0xFF1A2941),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0808),
    errorContainer = Color(0xFF531919),
    onErrorContainer = Color(0xFFFFCACA),
    surfaceDim = Color(0xFF020611),
    surfaceBright = Color(0xFF111D30),
    surfaceContainerLowest = Color(0xFF020611),
    surfaceContainerLow = Color(0xFF06101E),
    surfaceContainer = LevikDarkSurface,
    surfaceContainerHigh = LevikDarkSurfaceCard,
    surfaceContainerHighest = Color(0xFF111E31),
    scrim = Color.Black,
)

private val AmoledDarkColors = darkColorScheme(
    primary = LevikBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF122240),
    onPrimaryContainer = Color(0xFFBFDBFE),
    secondary = Color(0xFF818CF8),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF1C1844),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF032A17),
    tertiaryContainer = Color(0xFF0B3D28),
    onTertiaryContainer = Color(0xFF8FF0B8),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF7FAFF),
    surface = Color(0xFF0A0E17),
    onSurface = Color(0xFFF7FAFF),
    surfaceVariant = Color(0xFF101724),
    onSurfaceVariant = LevikDarkMutedText,
    surfaceTint = LevikBlue,
    inverseSurface = Color(0xFFEAF0FA),
    inverseOnSurface = Color(0xFF0A1220),
    inversePrimary = LevikDarkBlue,
    outline = Color(0xFF1A263B),
    outlineVariant = Color(0xFF162133),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0808),
    errorContainer = Color(0xFF531919),
    onErrorContainer = Color(0xFFFFCACA),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF111927),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050912),
    surfaceContainer = Color(0xFF090E18),
    surfaceContainerHigh = Color(0xFF0D1420),
    surfaceContainerHighest = Color(0xFF121B2A),
    scrim = Color.Black,
)

@Composable
fun LevikTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemInDark
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = when {
        themeMode == ThemeMode.AMOLED -> AmoledDarkColors
        isDark -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LevikTypography,
        shapes = LevikShapes,
        content = content,
    )
}

object LevikSwitchDefaults {
    @Composable
    fun colors(
        checkedThumbColor: Color = Color.White,
        checkedTrackColor: Color = MaterialTheme.colorScheme.primary,
        checkedBorderColor: Color = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor: Color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
            Color(0xFF64748B) // High contrast Slate-500 thumb on light background
        } else {
            Color(0xFF94A3B8) // Slate-400 thumb on dark background
        },
        uncheckedTrackColor: Color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
            Color(0xFFE2E8F0) // Slate-200 track on light background
        } else {
            Color(0xFF1E293B) // Slate-800 track on dark background
        },
        uncheckedBorderColor: Color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
            Color(0xFF94A3B8) // Slate-400 border contour on light background
        } else {
            Color(0xFF334155) // Slate-700 border contour on dark background
        },
    ): androidx.compose.material3.SwitchColors = androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = checkedThumbColor,
        checkedTrackColor = checkedTrackColor,
        checkedBorderColor = checkedBorderColor,
        uncheckedThumbColor = uncheckedThumbColor,
        uncheckedTrackColor = uncheckedTrackColor,
        uncheckedBorderColor = uncheckedBorderColor,
    )
}
