package com.nogirelay.app.ui.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.ChromaticAberrationMode
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.SurfaceProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The core liquid-glass material.
 *
 * One shared recipe for every surface in the app: real background blur and
 * refraction (Haze glass), a milky or accent-tinted translucent body, a
 * directional fresnel edge light, and a large soft ambient shadow.
 */

@Composable
fun rememberGlassHazeState(): HazeState = rememberHazeState()

/**
 * Screen root: paints the milky backdrop and registers it as the single
 * haze source that every glass surface on the screen samples. Nesting is
 * allowed (sheets host their own backdrop over the dimmed screen).
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    background: Brush = GlassColors.Backdrop,
    hazeState: HazeState = rememberGlassHazeState(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Box(
            Modifier
                .matchParentSize()
                .hazeSource(hazeState)
                .drawWithCache {
                    onDrawBehind { drawRect(background) }
                },
        )
        CompositionLocalProvider(LocalGlassHazeState provides hazeState) {
            content()
        }
    }
}

/** The two material moods: inactive milky glass vs. active accent glass. */
enum class GlassTone { Neutral, Accent, OnDark }

@Stable
class GlassMaterialSpec(
    val tone: GlassTone = GlassTone.Neutral,
    val fillAlpha: Float = Float.NaN,
    val blurDp: Float = Float.NaN,
    val specular: Float = GlassOpticsPresets.Specular,
    val tint: Color? = null,
)

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun glassStyle(
    shape: RoundedCornerShape,
    tone: GlassTone,
    fillAlpha: Float,
    blurDp: Dp,
    specular: Float,
    tintOverride: Color?,
): GlassStyle {
    val accent = GlassColors.Accent
    val body = when (tone) {
        GlassTone.Neutral -> Color.White
        GlassTone.Accent -> accent
        GlassTone.OnDark -> Color.White
    }
    val style = remember(shape, tone, fillAlpha, blurDp, specular, tintOverride) {
        GlassStyle.regular.then {
            optics(
                GlassDefaults.optics.copy(
                    depth = OpticalSizeValue.Fixed(1f),
                    blurRadius = OpticalSizeValue.Fixed(blurDp),
                ),
            )
            // The glass body never becomes a flat solid color: even the active
            // state keeps visible blur + refraction underneath the tint.
            backgroundColor(Color.Transparent)
            tint(
                tintOverride ?: body.copy(
                    alpha = if (tone == GlassTone.Accent) {
                        // Leave a little optical depth for the highlight and
                        // refraction layers instead of making a flat swatch.
                        (fillAlpha * 0.88f).coerceIn(0f, 1f)
                    } else fillAlpha,
                ),
            )
            shape(shape)
            surfaceProfile(SurfaceProfile.Squircle)
            specularIntensity(specular)
            ambientResponse(GlassOpticsPresets.Ambient)
            edgeSoftness(GlassOpticsPresets.EdgeSoftness.dp)
            fresnelExponent(GlassOpticsPresets.FresnelExponent)
            specularExponent(GlassOpticsPresets.SpecularExponent)
            chromaticAberrationStrength(GlassOpticsPresets.Chromatic)
            chromaticAberrationMode(ChromaticAberrationMode.Simple)
            lightPosition(androidx.compose.ui.Alignment.TopCenter)
        }
    }
    return style
}

/**
 * Applies the liquid-glass material to this element.
 *
 * @param interactionSource when provided, the glass responds to presses with
 * internal lighting: the highlight sinks inward and refraction tightens,
 * while the tint deepens slightly (handled by [glassPress] for geometry).
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glass(
    shape: RoundedCornerShape,
    tone: GlassTone = GlassTone.Neutral,
    fillAlpha: Float = when (tone) {
        GlassTone.Neutral -> GlassColors.NeutralFillAlpha
        GlassTone.Accent -> GlassColors.AccentFillAlpha
        GlassTone.OnDark -> 0.14f
    },
    blur: Dp = GlassOpticsPresets.BlurControl.dp,
    specular: Float = GlassOpticsPresets.Specular,
    tint: Color? = null,
    interactionSource: MutableInteractionSource? = null,
    pressedLighting: Boolean = true,
): Modifier {
    val hazeState = LocalGlassHazeState.current
    val blurEnabled = LocalGlassBlurEnabled.current
    val style = glassStyle(shape, tone, fillAlpha, blur, specular, tint)

    val pressStyle: (dev.chrisbanes.haze.glass.GlassStyleScope.() -> Unit)? =
        if (interactionSource != null && pressedLighting) {
            {
                // Press: snap in fast, release on a damped spring so the
                // lighting settles softly with a whisper of overshoot.
                interactionPositionAnimationSpec(GlassMotion.MorphOffsetSpec)
                pressed {
                    animate(GlassMotion.PressInSpec, GlassMotion.ReleaseSpec) {
                        // The finger presses INTO the glass: light sinks,
                        // refraction tightens, body deepens a touch.
                        lightingIntensity(0.85f)
                        refractionMultiplier(1.18f)
                        whitePointDelta(-0.05f)
                    }
                }
            }
        } else {
            null
        }
    val finalStyle = if (pressStyle != null) style.then(pressStyle) else style

    val surface = this.then(
        if (hazeState != null && blurEnabled) {
            Modifier.hazeGlass(
                input = HazeInput.Sources(hazeState),
                style = finalStyle,
                expandLayerBounds = true,
                interactionSource = interactionSource,
            )
        } else {
            // Fallback: soft translucent fill with the same color language.
            // Fallback is only reachable from overlay windows (popover /
            // dialog / sheet) with no haze source — those need a solid milky
            // body to stay readable over arbitrary content.
            val fallback = when (tone) {
                GlassTone.Neutral -> Color.White.copy(alpha = 0.94f)
                GlassTone.Accent -> GlassColors.Accent.copy(alpha = 0.92f)
                GlassTone.OnDark -> Color(0xFF26272E).copy(alpha = 0.88f)
            }
            Modifier.drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                onDrawBehind { drawOutline(outline, color = fallback) }
            }
        },
    )
    // Colored controls keep the app accent hue, but gain the same dimensional
    // surface as the reference: a soft upper highlight and a slightly deeper
    // lower body. This is an optical layer only; icons and layout are untouched.
    return if (tone == GlassTone.Accent) {
        surface.then(Modifier.accentSurfaceLight(shape))
    } else {
        surface
    }
}

private fun Modifier.accentSurfaceLight(shape: RoundedCornerShape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val top = lerp(GlassColors.Accent, Color.White, 0.25f)
    val bottom = lerp(GlassColors.AccentDeep, Color.Black, 0.10f)
    val body = Brush.verticalGradient(
        0f to top.copy(alpha = 0.24f),
        0.26f to Color.Transparent,
        0.68f to Color.Transparent,
        1f to bottom.copy(alpha = 0.24f),
    )
    val highlight = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.25f), Color.Transparent),
        center = Offset(size.width * 0.26f, size.height * 0.12f),
        radius = size.maxDimension * 0.72f,
    )
    onDrawWithContent {
        drawContent()
        drawOutline(outline, brush = body)
        drawOutline(outline, brush = highlight, blendMode = BlendMode.Screen)
    }
}

/**
 * Directional fresnel edge light: a gradient stroke that is bright at the
 * top edge and dissolves toward the bottom, plus a faint inner rim shade at
 * the very bottom. This is NOT a flat 1dp white border — the stroke alpha is
 * driven by two overlapping gradients to mimic rim refraction.
 */
fun Modifier.glassEdgeLight(
    shape: RoundedCornerShape,
    tone: GlassTone = GlassTone.Neutral,
    strength: Float = 1f,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val bright = when (tone) {
        GlassTone.Accent -> lerp(GlassColors.Accent, Color.White, 0.72f)
        else -> GlassColors.EdgeLight
    }
    val edgeStrength = if (tone == GlassTone.OnDark) strength * 0.55f else strength
    val rimBrush = Brush.linearGradient(
        0f to bright.copy(alpha = 0.85f * edgeStrength),
        0.28f to bright.copy(alpha = 0.38f * edgeStrength),
        0.62f to bright.copy(alpha = 0.10f * edgeStrength),
        1f to GlassColors.EdgeShade.copy(alpha = 0.16f * edgeStrength),
        start = Offset(size.width * 0.30f, 0f),
        end = Offset(size.width * 0.72f, size.height),
    )
    val innerGlow = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.30f * edgeStrength),
        0.16f to Color.Transparent,
        0.88f to Color.Transparent,
        1f to GlassColors.EdgeShade.copy(alpha = 0.10f * edgeStrength),
    )
    // A broad, low-alpha rim reads as a rounded surface; a thin bright outline
    // makes the control look like it has been drawn around the edge.
    val stroke = Stroke(width = 1.0f.dp.toPx())
    val glowStroke = Stroke(width = 2.2f.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawOutline(outline, brush = innerGlow, style = glowStroke, blendMode = BlendMode.SrcOver)
        drawOutline(outline, brush = rimBrush, style = stroke)
    }
}

/** Soft ambient shadow with the shared depth palette. */
fun Modifier.glassShadow(
    shape: Shape,
    depth: GlassDepth,
): Modifier = if (depth.elevation > 0.dp) {
    this.shadow(
        elevation = depth.elevation,
        shape = shape,
        clip = false,
        ambientColor = depth.ambient,
        spotColor = depth.spot,
    )
} else {
    this
}






