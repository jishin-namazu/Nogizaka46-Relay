package com.nogirelay.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val BrandPurple = Color(0xFF7A2A90)
val BrandPurpleDark = Color(0xFF4E175F)
val BrandPurpleLight = Color(0xFFF3EDF7)
val BrandPurpleSurface = Color(0xFFFBF8FC)
val BrandPurpleContainer = Color(0xFFEADBEE)
val BrandPurpleBorder = Color(0x267A2A90)

val SignalGreen = Color(0xFF14A46D)
val SignalCoral = Color(0xFFDB4F61)
val SignalCyan = Color(0xFF087E8B)
val SignalAmber = Color(0xFFF59E0B)

val NavigationTabIndicatorShape = RoundedCornerShape(12.dp)
val RelayControlShape = RoundedCornerShape(14.dp)
val RelayCardShape = RoundedCornerShape(18.dp)
val RelayBubbleShape = RoundedCornerShape(16.dp)

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
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = BrandPurpleContainer,
    onPrimaryContainer = Color(0xFF2F0B38),
    secondary = SignalCyan,
    onSecondary = Color.White,
    tertiary = SignalGreen,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = BrandPurpleLight,
    outline = Color(0xFF817882),
    outlineVariant = Color(0xFFE2DCE6),
    error = SignalCoral,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE3AFE9),
    secondary = Color(0xFF71D5DE),
    tertiary = Color(0xFF5EE0A5),
    background = Color(0xFF171318),
    surface = Color(0xFF211C22),
    surfaceVariant = Color(0xFF2C2530),
    error = Color(0xFFFFB2BC),
)

@Composable
fun NogiRelayTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides AppTypography.bodyMedium,
            content = content,
        )
    }
}
