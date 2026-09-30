package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nogirelay.app.ui.AppAccent
import com.nogirelay.app.ui.AppAccentDark
import com.nogirelay.app.ui.AppBackdropBase
import dev.chrisbanes.haze.HazeState

/**
 * Nogi Relay "Liquid Glass" design system.
 *
 * The whole app is built from one continuous liquid-glass material:
 * translucent, milky, softly lit, with real blur / refraction (Haze 2.0),
 * fresnel edge highlights and large soft ambient shadows. All interactive
 * elements share the same soft-body physics (squishy press, viscous drag,
 * damped-spring release, container morphing).
 *
 * This file holds the shared tokens: color, shape, depth and motion.
 */

// ---------------------------------------------------------------------------
// Color
// ---------------------------------------------------------------------------

object GlassColors {
    /** Clear white backdrop with a subtle cool depth gradient. */
    val BackdropTop = AppBackdropBase
    val BackdropMid = Color(0xFFF6F7F9)
    val BackdropBottom = Color(0xFFEFF1F4)
    val SheetSurface = Color(0xFFFAFBFC)
    val Backdrop: Brush = Brush.verticalGradient(
        0f to BackdropTop,
        0.55f to BackdropMid,
        1f to BackdropBottom,
    )

    /** Dark scrim used behind overlays and the media viewer. */
    val ScrimDark = Color(0xFF0E0E13)

    /** Primary content color on glass. */
    val Ink = Color(0xFF242229)
    val InkSecondary = Color(0xFF5B5762)
    val InkTertiary = Color(0xFF746F7C)

    /** Brighter control purple; brand text colors remain on the darker tokens. */
    // High-chroma purple for colored glass surfaces, with a deep companion
    // tone for the lower edge and the cast color.
    val Accent = Color(0xFF9446A6)
    val AccentDeep = Color(0xFF682B76)
    val AccentInk = AppAccentDark

    /** Neutral "inactive" glass: milky cool gray. */
    val NeutralTint = Color(0xFFFFFFFF)
    // The white calibration surface is mostly milky body with a little
    // background showing through, rather than a faint white outline.
    val NeutralFillAlpha = 0.68f
    val NeutralFillStrongAlpha = 0.78f

    /** Active glass keeps transparency: accent is a tint over blur. */
    val AccentFillAlpha = 0.92f
    val AccentFillStrongAlpha = 0.96f

    /** Edge highlight ring colors (fresnel-like, directional). */
    val EdgeLight = Color(0xFFFFFFFF)
    val EdgeShade = Color(0xFF95929C)

    /** Status colors. */
    val Success = Color(0xFF0E9F6E)
    val Warning = Color(0xFFF5A623)
    val Danger = Color(0xFFE2544B)

    /** On-accent content. */
    val OnAccent = Color(0xFFFFFFFF)

    fun accentGlassTint(alpha: Float = AccentFillAlpha): Color = Accent.copy(alpha = alpha)
}

// ---------------------------------------------------------------------------
// Shape
// ---------------------------------------------------------------------------

object GlassShapes {
    val Circle = CircleShape
    val Capsule = RoundedCornerShape(percent = 50)
    val Chip = RoundedCornerShape(percent = 50)
    val CardSmall = RoundedCornerShape(20.dp)
    val Card = RoundedCornerShape(28.dp)
    val CardLarge = RoundedCornerShape(34.dp)
    val Panel = RoundedCornerShape(32.dp)
    val Sheet = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
    val Bubble = RoundedCornerShape(24.dp)
    val Field = RoundedCornerShape(percent = 50)
    val Popover = RoundedCornerShape(26.dp)
}

// ---------------------------------------------------------------------------
// Depth: soft, large-radius, low-alpha ambient shadows
// ---------------------------------------------------------------------------

@Stable
data class GlassDepth(
    val elevation: Dp,
    val ambient: Color,
    val spot: Color,
)

object GlassDepths {
    /** Floating pill / small buttons hovering just above the backdrop. */
    val Low = GlassDepth(
        elevation = 16.dp,
        ambient = Color(0x142E3446),
        spot = Color(0x292E3446),
    )

    /** Cards and panels. */
    val Medium = GlassDepth(
        elevation = 18.dp,
        ambient = Color(0x122E3446),
        spot = Color(0x262E3446),
    )

    /** Floating navigation capsule, popovers, sheets. */
    val High = GlassDepth(
        elevation = 26.dp,
        ambient = Color(0x142E3446),
        spot = Color(0x302E3446),
    )

    /** Nothing: content that sits flush on the backdrop. */
    val None = GlassDepth(0.dp, Color.Transparent, Color.Transparent)
}

// ---------------------------------------------------------------------------
// Motion: one shared physics vocabulary
// ---------------------------------------------------------------------------

object GlassMotion {
    /**
     * Press-in: extremely fast and direct ("跟手"). The finger must feel the
     * surface give immediately, so the squash is nearly instant.
     */
    val PressInSpec = tween<Float>(durationMillis = 90)

    /**
     * Release: damped spring with a tiny amount of overshoot. Soft and
     * physical, never cartoonish.
     */
    val ReleaseSpec = spring<Float>(
        dampingRatio = 0.62f,
        stiffness = 420f,
    )

    /** Gentle spring for value changes (switch tint, enable fades). */
    val GentleSpec = spring<Float>(
        dampingRatio = 0.78f,
        stiffness = 380f,
    )

    /** Shape/bounds morphing (popover expand, card transforms). */
    val MorphSpec = spring<Float>(
        dampingRatio = 0.80f,
        stiffness = 340f,
    )
    val MorphDpSpec = spring<Dp>(
        dampingRatio = 0.80f,
        stiffness = 340f,
    )
    val MorphOffsetSpec = spring<Offset>(
        dampingRatio = 0.80f,
        stiffness = 340f,
    )

    /**
     * Liquid indicator edges. The leading edge is stiffer (moves first), the
     * trailing edge is softer (lags behind) which produces the liquid stretch.
     */
    val IndicatorLeadingSpec = spring<Float>(
        dampingRatio = 0.72f,
        stiffness = 620f,
    )
    val IndicatorTrailingSpec = spring<Float>(
        dampingRatio = 0.86f,
        stiffness = 240f,
    )

    /** Viscous drag follow: the glass body trails the finger. */
    val DragFollowSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 900f,
    )

    /** Settle after drag release / magnetic snap. */
    val SnapSpec = spring<Float>(
        dampingRatio = 0.68f,
        stiffness = 480f,
    )

    /** Late, subtle content appearance inside morphing containers. */
    const val ContentFadeInMillis = 130
    const val ContentFadeDelayMillis = 60
    const val ContentFadeOutMillis = 90
}

// ---------------------------------------------------------------------------
// Glass optics presets
// ---------------------------------------------------------------------------

object GlassOpticsPresets {
    /** Standard floating controls (nav capsule, circle buttons). */
    const val BlurControl = 28f // dp
    /** Cards / panels. */
    const val BlurPanel = 40f
    /** Message bubbles inside lists: keep cheap. */
    const val BlurBubble = 18f
    /** Sheets / popovers over dimmed content. */
    const val BlurOverlay = 48f

    const val Specular = 0.9f
    const val Ambient = 0.32f
    const val Chromatic = 0.08f
    const val EdgeSoftness = 1.2f // dp
    const val FresnelExponent = 2.6f
    const val SpecularExponent = 20f
}

// ---------------------------------------------------------------------------
// Composition locals
// ---------------------------------------------------------------------------

/** Screen-scoped shared [HazeState]; every glass surface reads from it. */
val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

/** When false, glass falls back to translucent fills (lists, weak devices). */
val LocalGlassBlurEnabled = staticCompositionLocalOf { true }
