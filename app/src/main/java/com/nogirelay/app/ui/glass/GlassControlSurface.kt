package com.nogirelay.app.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** A cool, translucent body leaves headroom for the bevel highlights. */
internal val GlassControlBody: Color get() = GlassColors.ControlBody

internal fun controlFillAlpha(tone: GlassTone): Float = when (tone) {
    GlassTone.Neutral -> 0.82f
    GlassTone.Accent -> 0.98f
    GlassTone.OnDark -> 0.14f
}

internal fun controlBodyColor(tone: GlassTone, tint: Color?): Color = tint ?: when (tone) {
    GlassTone.Neutral -> GlassControlBody
    GlassTone.Accent -> GlassColors.Accent
    GlassTone.OnDark -> Color.White
}

/** Raise radiance without washing a colored edge out to pastel white. */
private fun controlRimColor(body: Color): Color = Color(
    red = (body.red * 1.24f).coerceAtMost(1f),
    green = (body.green * 1.24f).coerceAtMost(1f),
    blue = (body.blue * 1.24f).coerceAtMost(1f),
)

/** Broad cast shadow and a restrained colored bounce, outside the surface clip. */
/** The ambient cast shadow [glassControlShadow] draws under a control. */
internal fun glassControlCastShadow(
    depth: GlassDepth,
    compression: Float = 1f,
    opacity: Float = 1f,
): Shadow {
    val scale = (depth.elevation.value / 16f).coerceIn(0.5f, 1.35f)
    return Shadow(
        radius = (14f * scale * compression).dp,
        spread = (-2f * scale).dp,
        offset = DpOffset(0.dp, (7f * scale * compression).dp),
        color = GlassColors.Shadow.copy(
            alpha = (0.14f * compression * opacity * GlassColors.ShadowStrength).coerceIn(0f, 1f),
        ),
    )
}

internal fun Modifier.glassControlShadow(
    shape: RoundedCornerShape,
    tone: GlassTone = GlassTone.Neutral,
    tint: Color? = null,
    depth: GlassDepth = GlassDepths.Low,
    press: GlassPress? = null,
    alpha: Float = 1f,
): Modifier {
    if (depth.elevation <= 0.dp) return this
    val scale = (depth.elevation.value / 16f).coerceIn(0.5f, 1.35f)
    val compression = press?.shadowFactor ?: 1f
    val opacity = alpha.coerceIn(0f, 1f)
    val cast = dropShadow(shape, glassControlCastShadow(depth, compression, opacity))
    return if (tone == GlassTone.Accent) {
        cast.dropShadow(
            shape,
            Shadow(
                radius = (7f * scale * compression).dp,
                spread = (-2f * scale).dp,
                offset = DpOffset(0.dp, (3f * scale * compression).dp),
                color = controlRimColor(controlBodyColor(tone, tint)).copy(alpha = 0.18f * compression * opacity),
            ),
        )
    } else cast
}

/**
 * A shallow rounded bevel: fine upper light, a wider lower light, no dark stroke.
 * All light is drawn before content, so labels and glyphs keep their own colors.
 * Bevel widths use the short axis and stay narrow on both circles and long pills.
 */
internal fun Modifier.glassControlFinish(
    shape: RoundedCornerShape,
    tone: GlassTone,
    tint: Color?,
    strength: Float = 1f,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val lighting = GlassControlLighting(tone, tint, strength, size.minDimension, density)
    onDrawWithContent {
        lighting.draw(this, outline)
        drawContent()
    }
}

/** Cached lighting also used by moving progress fills, without rebuilding a blur layer. */
internal class GlassControlLighting(
    tone: GlassTone,
    tint: Color?,
    strength: Float,
    shortAxis: Float,
    density: Float,
) {
    private val colored = tone == GlassTone.Accent
    private val base = controlBodyColor(tone, tint)
    private val rim = if (colored) controlRimColor(base) else GlassColors.RimLight
    // Dark glass keeps a much fainter bevel so controls don't read as outlines.
    private val lightStrength = strength.coerceIn(0f, 1f) * if (colored) 1f else GlassColors.RimStrength
    private val surface = Brush.verticalGradient(
        0f to (if (colored) lerp(base, rim, 0.4f) else rim).copy(alpha = 0.10f * if (colored) 1f else GlassColors.RimStrength),
        0.28f to Color.Transparent,
        0.70f to Color.Transparent,
        1f to rim.copy(alpha = if (colored) 0.16f else 0.10f),
    )
    private val bevel = (shortAxis * 0.028f).coerceIn(0.8f * density, 2.2f * density)
    private val softRims = (4 downTo 1).map { step ->
        val alpha = when (step) {
            4 -> 0.035f
            3 -> 0.055f
            2 -> 0.09f
            else -> 0.16f
        }
        Brush.verticalGradient(
            0f to rim.copy(alpha = alpha * 0.45f * lightStrength),
            0.20f to Color.Transparent,
            0.68f to Color.Transparent,
            1f to rim.copy(alpha = alpha * lightStrength),
        ) to Stroke(width = bevel * step * 0.65f)
    }
    private val edge = Brush.verticalGradient(
        0f to rim.copy(alpha = 0.78f * lightStrength),
        0.20f to rim.copy(alpha = 0.14f * lightStrength),
        0.60f to rim.copy(alpha = 0.06f * lightStrength),
        1f to rim.copy(alpha = 0.90f * lightStrength),
    )
    private val edgeStroke = Stroke(width = 1.2f * density)

    fun draw(scope: DrawScope, outline: Outline) = with(scope) {
        drawOutline(outline, brush = surface)
        softRims.forEach { (brush, stroke) -> drawOutline(outline, brush = brush, style = stroke) }
        drawOutline(outline, brush = edge, style = edgeStroke)
    }
}
