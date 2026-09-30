package com.nogirelay.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

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
    tertiary = Color(0xFF5EE0A5),
    background = Color(0xFF18151D),
    surface = Color(0xFF211D27),
    surfaceVariant = Color(0xFF342C3D),
    error = Color(0xFFFFB2BC),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NogiRelayTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides AppTypography.bodyMedium,
            LocalRippleConfiguration provides null,
            content = content,
        )
    }
}
