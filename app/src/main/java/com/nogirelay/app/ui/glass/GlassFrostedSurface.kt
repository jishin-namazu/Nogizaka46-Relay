package com.nogirelay.app.ui.glass

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/** Opt in only overlay controls whose backdrop contains actual page content. */
val LocalGlassFrostedControls = staticCompositionLocalOf { false }

/** Page capture plus lower overlay windows, ordered independently of Compose windows. */
val LocalGlassOverlayHazeState = staticCompositionLocalOf<HazeState?> { null }
internal val LocalGlassOverlayLevel = staticCompositionLocalOf { 0f }

internal data class GlassOverlayLayer(val state: HazeState?, val level: Float)

internal fun Modifier.glassOverlaySource(layer: GlassOverlayLayer): Modifier =
    then(layer.state?.let { Modifier.hazeSource(it, zIndex = layer.level) } ?: Modifier)

@Composable
internal fun rememberGlassOverlayLayer(minimumLevel: Float): GlassOverlayLayer {
    val state = LocalGlassOverlayHazeState.current ?: LocalGlassHazeState.current
    val level = maxOf(LocalGlassOverlayLevel.current + 1f, minimumLevel)
    return remember(state, level) { GlassOverlayLayer(state, level) }
}

@Composable
internal fun Modifier.glassOverlaySurface(
    layer: GlassOverlayLayer,
    shape: RoundedCornerShape,
    fillAlpha: Float = 0.52f,
    blur: Dp = 28.dp,
): Modifier {
    val selection = remember(layer.level) {
        HazeSourceSelection.All.where { it.zIndex < layer.level }
    }
    return glassOverlaySource(layer)
        .frostedGlass(
            shape = shape, fillAlpha = fillAlpha, blur = blur,
            sourceState = layer.state, sourceSelection = selection,
        )
}

@Composable
fun Modifier.frostedGlass(
    shape: RoundedCornerShape,
    tone: GlassTone = GlassTone.Neutral,
    tint: Color? = null,
    fillAlpha: Float = 0.42f,
    blur: Dp = 22.dp,
    edgeStrength: Float = 1f,
    sourceState: HazeState? = LocalGlassHazeState.current,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
): Modifier {
    val state = sourceState
    val enabled = LocalGlassBlurEnabled.current && Build.VERSION.SDK_INT >= 31 && state != null
    val controlBody = GlassControlBody
    val style = remember(blur, controlBody) {
        HazeBlurStyle {
            blurRadius(blur)
            backgroundColor(controlBody)
            colorEffects(emptyList())
            noiseFactor(0.01f)
        }
    }
    val body = controlBodyColor(tone, tint)
    val opacity = if (enabled) fillAlpha else maxOf(fillAlpha, 0.88f)
    return clip(shape)
        .then(if (enabled) {
            Modifier.hazeBlur(input = HazeInput.Sources(state, selection = sourceSelection), style = style)
        } else Modifier)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawBehind { drawOutline(outline, body.copy(alpha = body.alpha * opacity)) }
        }
        .glassControlFinish(shape, tone, tint, edgeStrength)
}

/** Strong at the status bar, fading continuously to the unblurred timeline. */
@Composable
fun Modifier.progressiveGlassHeader(state: HazeState): Modifier {
    val enabled = LocalGlassBlurEnabled.current && Build.VERSION.SDK_INT >= 31
    val controlBody = GlassControlBody
    val style = remember(controlBody) {
        HazeBlurStyle {
            blurRadius(28.dp)
            backgroundColor(controlBody)
            colorEffects(emptyList())
            noiseFactor(0f)
            progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f))
            mask(Brush.verticalGradient(
                0f to Color.Black,
                0.55f to Color.Black,
                1f to Color.Transparent,
            ))
        }
    }
    return then(if (enabled) {
        Modifier.hazeBlur(input = HazeInput.Sources(state), style = style)
    } else Modifier)
        .drawWithCache {
            val veil = Brush.verticalGradient(
                0f to GlassControlBody.copy(alpha = if (enabled) 0.46f else 0.94f),
                0.5f to GlassControlBody.copy(alpha = if (enabled) 0.22f else 0.66f),
                1f to Color.Transparent,
            )
            onDrawBehind { drawRect(veil) }
        }
}
