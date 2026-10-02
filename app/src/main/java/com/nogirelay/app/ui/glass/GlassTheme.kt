package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/**
 * Every color the glass system paints with. Two instances exist: the milky
 * light glass and a smoky dark glass; [GlassColors] reads whichever one the
 * system appearance selects.
 */
@Immutable
data class GlassPalette(
    val isDark: Boolean,
    val backdropTop: Color,
    val backdropMid: Color,
    val backdropBottom: Color,
    val sheetSurface: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentInk: Color,
    /** Body of neutral glass panels; light glass is white, dark glass is smoke. */
    val neutralBody: Color,
    /** Body of neutral controls (buttons, fields, tracks). */
    val controlBody: Color,
    /** Neutral panel fill used when blur is unavailable. */
    val neutralFallback: Color,
    /** Bevel and rim light drawn on top of controls. */
    val rimLight: Color,
    /** Scales how strongly rims and bevels shine. */
    val rimStrength: Float,
    val edgeLight: Color,
    val edgeShade: Color,
    /** Cast shadow color for glass elements. */
    val shadow: Color,
    val shadowStrength: Float,
    val scrim: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val onAccent: Color,
    val neutralFillAlpha: Float,
    val neutralFillStrongAlpha: Float,
    /** Scales specular and ambient reflections of the refractive glass. */
    val glassLight: Float,
    /** Opaque fill shown while a remote image loads. */
    val placeholder: Color,
) {
    val backdrop: Brush = Brush.verticalGradient(
        0f to backdropTop,
        0.55f to backdropMid,
        1f to backdropBottom,
    )

    companion object {
        val Light = GlassPalette(
            isDark = false,
            backdropTop = AppBackdropBase,
            backdropMid = Color(0xFFF6F7F9),
            backdropBottom = Color(0xFFEFF1F4),
            sheetSurface = Color(0xFFFAFBFC),
            ink = Color(0xFF242229),
            inkSecondary = Color(0xFF5B5762),
            inkTertiary = Color(0xFF746F7C),
            accent = Color(0xFF9446A6),
            accentDeep = Color(0xFF682B76),
            accentInk = AppAccentDark,
            neutralBody = Color.White,
            controlBody = Color(0xFFF4F6F6),
            neutralFallback = Color.White.copy(alpha = 0.94f),
            rimLight = Color.White,
            rimStrength = 1f,
            edgeLight = Color.White,
            edgeShade = Color(0xFF95929C),
            shadow = Color(0xFF4C5360),
            shadowStrength = 1f,
            scrim = Color(0x33090A10),
            success = Color(0xFF0E9F6E),
            warning = Color(0xFFF5A623),
            danger = Color(0xFFE2544B),
            onAccent = Color.White,
            neutralFillAlpha = 0.68f,
            neutralFillStrongAlpha = 0.78f,
            glassLight = 1f,
            placeholder = Color(0xFFE7E2EA),
        )

        val Dark = GlassPalette(
            isDark = true,
            backdropTop = Color(0xFF17151C),
            backdropMid = Color(0xFF131218),
            backdropBottom = Color(0xFF0E0D12),
            sheetSurface = Color(0xFF1C1A22),
            ink = Color(0xFFF0EDF4),
            inkSecondary = Color(0xFFBAB5C3),
            inkTertiary = Color(0xFF8F8999),
            accent = Color(0xFFB875C8),
            accentDeep = Color(0xFF7B3B8A),
            accentInk = Color(0xFFE4B1EC),
            neutralBody = Color(0xFF2B2833),
            controlBody = Color(0xFF302D38),
            neutralFallback = Color(0xFF24212B).copy(alpha = 0.96f),
            rimLight = Color.White,
            rimStrength = 0.38f,
            edgeLight = Color(0xFFD9D4E2),
            edgeShade = Color.Black,
            shadow = Color.Black,
            shadowStrength = 2.2f,
            scrim = Color(0x66000000),
            success = Color(0xFF3CC891),
            warning = Color(0xFFF7B955),
            danger = Color(0xFFFF7A70),
            onAccent = Color.White,
            neutralFillAlpha = 0.72f,
            neutralFillStrongAlpha = 0.82f,
            glassLight = 0.45f,
            placeholder = Color(0xFF2E2A35),
        )
    }
}

/**
 * The active palette. It follows the system appearance, which every activity
 * shares, so one process-wide value is enough. It is snapshot state, so draw
 * blocks that read it are invalidated when it changes.
 */
object GlassColors {
    var palette: GlassPalette by mutableStateOf(GlassPalette.Light)
        private set

    fun apply(dark: Boolean) {
        val next = if (dark) GlassPalette.Dark else GlassPalette.Light
        if (palette !== next) palette = next
    }

    val isDark: Boolean get() = palette.isDark

    /** Clear backdrop with a subtle cool depth gradient. */
    val BackdropTop: Color get() = palette.backdropTop
    val BackdropMid: Color get() = palette.backdropMid
    val BackdropBottom: Color get() = palette.backdropBottom
    val SheetSurface: Color get() = palette.sheetSurface
    val Backdrop: Brush get() = palette.backdrop

    /** Dark scrim used behind the media viewer; identical in both palettes. */
    val ScrimDark = Color(0xFF0E0E13)
    /** Dim layer behind sheets and dialogs. */
    val Scrim: Color get() = palette.scrim

    /** Primary content color on glass. */
    val Ink: Color get() = palette.ink
    val InkSecondary: Color get() = palette.inkSecondary
    val InkTertiary: Color get() = palette.inkTertiary

    // High-chroma purple for colored glass surfaces, with a deep companion
    // tone for the lower edge and the cast color.
    val Accent: Color get() = palette.accent
    val AccentDeep: Color get() = palette.accentDeep
    val AccentInk: Color get() = palette.accentInk

    /** Neutral "inactive" glass body. */
    val NeutralTint: Color get() = palette.neutralBody
    val ControlBody: Color get() = palette.controlBody
    val NeutralFallback: Color get() = palette.neutralFallback
    // The calibration surface is mostly milky body with a little background
    // showing through, rather than a faint outline.
    val NeutralFillAlpha: Float get() = palette.neutralFillAlpha
    val NeutralFillStrongAlpha: Float get() = palette.neutralFillStrongAlpha

    /** Active glass keeps transparency: accent is a tint over blur. */
    const val AccentFillAlpha = 0.92f
    const val AccentFillStrongAlpha = 0.96f

    /** Edge highlight ring colors (fresnel-like, directional). */
    val EdgeLight: Color get() = palette.edgeLight
    val EdgeShade: Color get() = palette.edgeShade
    val RimLight: Color get() = palette.rimLight
    val RimStrength: Float get() = palette.rimStrength
    val Shadow: Color get() = palette.shadow
    val ShadowStrength: Float get() = palette.shadowStrength
    val GlassLight: Float get() = palette.glassLight
    val Placeholder: Color get() = palette.placeholder

    /** Status colors. */
    val Success: Color get() = palette.success
    val Warning: Color get() = palette.warning
    val Danger: Color get() = palette.danger

    /** On-accent content. */
    val OnAccent: Color get() = palette.onAccent

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
// Metrics
// ---------------------------------------------------------------------------

object GlassMetrics {
    /**
     * Height shared by every capsule control: buttons, chips, date pickers,
     * search fields, segmented tabs, sliders and entry rows ("媒体", "收藏夹"),
     * so a row of mixed controls lines up.
     */
    val ControlHeight = 44.dp

    /** Inline actions inside rows and cards (e.g. "开启"). */
    val CompactControlHeight = 32.dp
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
    val Low: GlassDepth get() = depth(16.dp, 0x14, 0x29)

    /** Cards and panels. */
    val Medium: GlassDepth get() = depth(18.dp, 0x12, 0x26)

    /** Floating navigation capsule, popovers, sheets. */
    val High: GlassDepth get() = depth(26.dp, 0x14, 0x30)

    /** Nothing: content that sits flush on the backdrop. */
    val None = GlassDepth(0.dp, Color.Transparent, Color.Transparent)

    private val LightBase = Color(0xFF2E3446)

    private fun depth(elevation: Dp, ambientAlpha: Int, spotAlpha: Int): GlassDepth {
        val palette = GlassColors.palette
        val base = if (palette.isDark) Color.Black else LightBase
        val strength = palette.shadowStrength
        return GlassDepth(
            elevation = elevation,
            ambient = base.copy(alpha = (ambientAlpha / 255f * strength).coerceAtMost(1f)),
            spot = base.copy(alpha = (spotAlpha / 255f * strength).coerceAtMost(1f)),
        )
    }
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
