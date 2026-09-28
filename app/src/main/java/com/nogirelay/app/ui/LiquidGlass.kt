package com.nogirelay.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Shared source state for surfaces which sit above the app's scrolling content. */
val LocalRelayHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun rememberRelayHazeState(): HazeState = rememberHazeState()

@Composable
fun ProvideRelayHazeState(
    hazeState: HazeState,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRelayHazeState provides hazeState, content = content)
}

/**
 * Applies the shared glass treatment while keeping the original component bounds intact.
 * Haze supplies the optical effect and the translucent surface remains readable on fallback
 * renderers that cannot capture a source.
 * An explicit [blurRadius] requests full diffusion over an opaque sampling base; compact
 * controls that omit it keep the regular, size-dependent material.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.relayGlass(
    shape: RoundedCornerShape = RelayCardShape,
    tint: Color = Color.White.copy(alpha = 0.08f),
    borderColor: Color = Color.White.copy(alpha = 0.5f),
    blurRadius: Dp = Dp.Unspecified,
    useWindowBackdrop: Boolean = true,
    specularIntensity: Float = 0.62f,
    ambientResponse: Float = 0.38f,
    preserveSourceColors: Boolean = false,
): Modifier {
    val hazeState = LocalRelayHazeState.current
    val surface = MaterialTheme.colorScheme.surface
    // Keep the sampled backdrop diffused while allowing the page's colour fields to remain
    // visible through the surface. The Haze input is still blurred before this translucent
    // base is composited, so the original sharp page is never exposed.
    val background = surface.copy(
        alpha = when {
            preserveSourceColors && blurRadius.isSpecified -> 0.08f
            blurRadius.isSpecified -> 0.34f
            else -> 0.24f
        },
    )
    val style = remember(shape, tint, background, blurRadius, specularIntensity, ambientResponse, preserveSourceColors) {
        GlassStyle.regular.then {
            if (blurRadius.isSpecified) {
                // Full depth prevents the sharp source from bleeding through the blur.
                optics(
                    GlassDefaults.optics.copy(
                        depth = OpticalSizeValue.Fixed(1f),
                        blurRadius = OpticalSizeValue.Fixed(blurRadius),
                    ),
                )
            }
            backgroundColor(background)
            tint(tint)
            shape(shape)
            specularIntensity(specularIntensity)
            ambientResponse(ambientResponse)
            if (preserveSourceColors) {
                // Regular glass mixes the source 55% towards white. A background-only source
                // needs its original colours, while retaining the full blur and edge optics.
                whitePoint(0f)
                chromaMultiplier(1f)
            }
            edgeSoftness(1.dp)
            chromaticAberrationStrength(0.08f)
        }
    }

    return this
        .then(
            if (hazeState != null) {
                Modifier.hazeGlass(
                    // Prefer a window backdrop when the platform supports it; the explicit
                    // source keeps the same result on older Android renderers.
                    input = if (useWindowBackdrop) {
                        HazeInput.Backdrop(fallback = HazeInput.Sources(hazeState))
                    } else {
                        HazeInput.Sources(hazeState)
                    },
                    style = style,
                )
            } else {
                Modifier
            },
        )
        .then(if (borderColor.alpha > 0f) Modifier.border(1.dp, borderColor, shape) else Modifier)
}

/**
 * Painted reflections only: place before the glass/background modifier to finish its surface.
 * Every fill and rim follows [shape]; this never captures the page or clips a parent/animation.
 * [reflectionTint] is an accent hue from which distinct light and dark reflections are derived.
 * [emphasizeEdges] uses one soft, directional reflection for card edges.
 */
fun Modifier.relayMirrorSheen(
    shape: RoundedCornerShape,
    strength: Float = 1f,
    reflectionTint: Color? = null,
    emphasizeEdges: Boolean = false,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    // Preserve the reflection's light/dark structure when changing its hue. Replacing every
    // stop with one pale container color flattens the curved glass into a solid-looking fill.
    val bodyMidtone = reflectionTint?.let { lerp(it, Color.White, 0.72f) } ?: Color(0xFFBCCCE2)
    val bodyLight = reflectionTint?.let { lerp(it, Color.White, 0.90f) } ?: Color(0xFFE5F4FF)
    val rimShade = reflectionTint?.let { lerp(it, Color.White, 0.28f) } ?: Color(0xFF7185A3)
    val rimLight = reflectionTint?.let { lerp(it, Color.White, 0.86f) } ?: Color(0xFFD5E7F7)
    val rimAccent = reflectionTint?.let { lerp(it, Color.White, 0.89f) } ?: Color(0xFFE2E9FF)
    val surfaceReflection = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.24f * strength),
        0.30f to Color.White.copy(alpha = 0.06f * strength),
        0.58f to bodyMidtone.copy(alpha = 0.04f * strength),
        1f to bodyLight.copy(alpha = 0.18f * strength),
        endY = size.height,
    )
    val diagonalReflection = Brush.linearGradient(
        0f to Color.Transparent,
        0.20f to Color.Transparent,
        0.36f to Color.White.copy(alpha = 0.30f * strength),
        0.48f to Color.White.copy(alpha = 0.04f * strength),
        0.64f to Color.Transparent,
        1f to Color.Transparent,
        start = Offset(size.width * 0.08f, 0f),
        end = Offset(size.width * 0.86f, size.height * 1.5f),
    )
    val rimReflection = if (emphasizeEdges) {
        // A single 1dp visible rim catches light softly along the rounded contour.
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.88f * strength),
            0.30f to Color.White.copy(alpha = 0.42f * strength),
            0.58f to rimShade.copy(alpha = 0.10f * strength),
            0.84f to Color.White.copy(alpha = 0.62f * strength),
            1f to rimShade.copy(alpha = 0.12f * strength),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    } else {
        Brush.sweepGradient(
            0f to rimShade.copy(alpha = 0.22f * strength),
            0.12f to Color.White.copy(alpha = 0.84f * strength),
            0.26f to rimLight.copy(alpha = 0.48f * strength),
            0.40f to rimShade.copy(alpha = 0.20f * strength),
            0.56f to Color.White.copy(alpha = 0.72f * strength),
            0.74f to Color.White.copy(alpha = 0.96f * strength),
            0.88f to rimAccent.copy(alpha = 0.48f * strength),
            1f to rimShade.copy(alpha = 0.22f * strength),
            center = Offset(size.width / 2f, size.height / 2f),
        )
    }
    val rimStroke = Stroke(width = (if (emphasizeEdges) 2.dp else 1.5.dp).toPx())

    onDrawWithContent {
        drawContent()
        drawOutline(outline, surfaceReflection)
        drawOutline(outline, diagonalReflection)
        drawOutline(outline, rimReflection, style = rimStroke)
    }
}

@OptIn(ExperimentalHazeApi::class)
fun Modifier.relayHazeSource(hazeState: HazeState): Modifier = hazeSource(hazeState)

/** Soft colour fields keep the captured backdrop alive while scrolling beneath glass surfaces. */
fun Modifier.relayAtmosphere(): Modifier = drawBehind {
    val radius = size.minDimension * 0.62f
    val lavenderCenter = Offset(size.width * 0.08f, size.height * 0.22f)
    val cyanCenter = Offset(size.width * 0.94f, size.height * 0.46f)
    val coralCenter = Offset(size.width * 0.58f, size.height * 0.96f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x337A2A90), Color.Transparent),
            center = lavenderCenter,
            radius = radius,
        ),
        radius = radius,
        center = lavenderCenter,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x32087E8B), Color.Transparent),
            center = cyanCenter,
            radius = radius * 0.9f,
        ),
        radius = radius * 0.9f,
        center = cyanCenter,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x29DB4F61), Color.Transparent),
            center = coralCenter,
            radius = radius * 0.8f,
        ),
        radius = radius * 0.8f,
        center = coralCenter,
    )
}
