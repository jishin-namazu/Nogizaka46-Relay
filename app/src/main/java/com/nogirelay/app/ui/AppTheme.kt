package com.nogirelay.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.core.view.WindowCompat
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.ThemeMode
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.LocalGlassBlurEnabled
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import com.nogirelay.app.ui.glass.rememberGlassQuality

/**
 * Brand colors. The visual system (shape / depth / glass material / motion)
 * lives in `ui.glass`; this file keeps only identity colors and typography.
 */
// Nogizaka46's signature purple.
val AppAccent = Color(0xFF812990)
val AppAccentDark = Color(0xFF5B1767)
val AppAccentContainer = Color(0xFFF2E3F5)

val SignalGreen = Color(0xFF14A46D)
val SignalCoral = Color(0xFFDB4F61)
val SignalCyan = Color(0xFF087E8B)

/** Milky backdrop base; mirrors GlassColors.BackdropTop. */
val AppBackdropBase = Color(0xFFFAFBFC)

private val defaultLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

@Suppress("DEPRECATION")
private val defaultPlatformTextStyle = PlatformTextStyle(
    includeFontPadding = false,
)

fun TextStyle.withFixes(): TextStyle = this.copy(
    platformStyle = defaultPlatformTextStyle,
    lineHeightStyle = defaultLineHeightStyle,
)

private val defaultTypography = Typography()
val AppTypography = Typography(
    displayLarge = defaultTypography.displayLarge.withFixes(),
    displayMedium = defaultTypography.displayMedium.withFixes(),
    displaySmall = defaultTypography.displaySmall.withFixes(),
    headlineLarge = defaultTypography.headlineLarge.withFixes(),
    headlineMedium = defaultTypography.headlineMedium.withFixes(),
    headlineSmall = defaultTypography.headlineSmall.withFixes(),
    titleLarge = defaultTypography.titleLarge.withFixes(),
    titleMedium = defaultTypography.titleMedium.withFixes(),
    titleSmall = defaultTypography.titleSmall.withFixes(),
    bodyLarge = defaultTypography.bodyLarge.withFixes(),
    bodyMedium = defaultTypography.bodyMedium.withFixes(),
    bodySmall = defaultTypography.bodySmall.withFixes(),
    labelLarge = defaultTypography.labelLarge.withFixes(),
    labelMedium = defaultTypography.labelMedium.withFixes(),
    labelSmall = defaultTypography.labelSmall.withFixes(),
)

private val LightColors = lightColorScheme(
    primary = AppAccent,
    onPrimary = Color.White,
    primaryContainer = AppAccentContainer,
    onPrimaryContainer = Color(0xFF390D42),
    secondary = SignalCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2F4),
    onSecondaryContainer = Color(0xFF164D56),
    tertiary = SignalGreen,
    background = AppBackdropBase,
    surface = Color.White,
    onBackground = Color(0xFF242229),
    onSurface = Color(0xFF242229),
    onSurfaceVariant = Color(0xFF5B5762),
    surfaceVariant = Color(0xFFF3F4F6),
    surfaceTint = AppAccent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF2F3F5),
    surfaceContainerHigh = Color(0xFFECEEF1),
    surfaceContainerHighest = Color(0xFFE5E8EC),
    outline = Color(0xFF807C86),
    outlineVariant = Color(0xFFE0DFE4),
    error = SignalCoral,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE4B1EC),
    onPrimary = Color(0xFF450C51),
    primaryContainer = AppAccentDark,
    onPrimaryContainer = Color(0xFFF8D8FC),
    secondary = Color(0xFF71D5DE),
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF1D4A50),
    onSecondaryContainer = Color(0xFFCDEFF2),
    tertiary = Color(0xFF5EE0A5),
    background = Color(0xFF17151C),
    onBackground = Color(0xFFF0EDF4),
    surface = Color(0xFF1C1A22),
    onSurface = Color(0xFFF0EDF4),
    onSurfaceVariant = Color(0xFFBAB5C3),
    surfaceVariant = Color(0xFF2B2833),
    surfaceTint = Color(0xFFE4B1EC),
    surfaceContainerLowest = Color(0xFF0E0D12),
    surfaceContainerLow = Color(0xFF17151C),
    surfaceContainer = Color(0xFF1C1A22),
    surfaceContainerHigh = Color(0xFF26232D),
    surfaceContainerHighest = Color(0xFF302D38),
    outline = Color(0xFF8F8999),
    outlineVariant = Color(0xFF3A3642),
    error = Color(0xFFFFB2BC),
    errorContainer = Color(0xFF7A2731),
    onErrorContainer = Color(0xFFFFDADD),
)

/** Whether the system appearance is dark, outside of composition. */
fun Context.isSystemInDarkMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Whether the app is dark under the chosen [ThemeMode], outside of composition. */
fun Context.isAppInDarkMode(): Boolean {
    val mode = if (AppGraph.isInitialized) AppGraph.settings.themeMode.value else ThemeMode.SYSTEM
    return when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkMode()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/** Whether the app is dark under the chosen [ThemeMode]; follows changes live. */
@Composable
fun rememberAppDarkTheme(): Boolean {
    val mode = if (AppGraph.isInitialized) {
        AppGraph.settings.themeMode.collectAsState().value
    } else {
        ThemeMode.SYSTEM
    }
    val systemDark = isSystemInDarkTheme()
    return when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/** Keeps this window's status and navigation bar icons legible on the app theme. */
@Composable
fun SyncSystemBarsWithTheme() {
    val view = LocalView.current
    val dark = GlassColors.isDark
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/**
 * App theme. The glass palette follows the chosen appearance (system, light
 * or dark); a screen that is dark by design (media viewer, glass call) still
 * forces [darkTheme] for its Material components.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NogiRelayTheme(
    darkTheme: Boolean = rememberAppDarkTheme(),
    content: @Composable () -> Unit,
) {
    GlassColors.apply(rememberAppDarkTheme())
    val quality = rememberGlassQuality()
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides AppTypography.bodyMedium,
            LocalRippleConfiguration provides null,
            LocalGlassBlurEnabled provides quality.blur,
            LocalGlassReducedMotion provides quality.reducedMotion,
            content = content,
        )
    }
}
