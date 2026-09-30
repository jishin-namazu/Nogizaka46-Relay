package com.nogirelay.app.ui.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nogirelay.app.performance.LocalRelayPageActive
import com.nogirelay.app.performance.LocalRelayPageWorkPaused
import com.nogirelay.app.performance.isRelayUiStarted
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos

/** Null means that the work has not supplied a total yet. */
@Composable
fun GlassLinearProgressIndicator(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    color: Color = GlassColors.Accent,
    animationKey: Any? = Unit,
    animationSpec: AnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 200f),
    onCompleted: (() -> Unit)? = null,
    minimumDurationMillis: Int = 0,
) {
    val target = progress?.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
    // Keep the current velocity when reports arrive before the spring settles.
    // Changing the request/phase starts a new animation with its own progress.
    val animated = key(animationKey) {
        val startedAt = remember { SystemClock.elapsedRealtime() }
        // A very short job can report its final value before the first frame.
        // Start that presentation at zero, without inventing intermediate work.
        var presentedTarget by remember {
            mutableFloatStateOf(if (minimumDurationMillis > 0) 0f else target ?: 0f)
        }
        LaunchedEffect(target) { presentedTarget = target ?: 0f }
        val minimumRemaining = remember(target, minimumDurationMillis) {
            if (target == 1f) {
                (minimumDurationMillis - (SystemClock.elapsedRealtime() - startedAt))
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            } else 0
        }
        animateFloatAsState(
            targetValue = presentedTarget,
            animationSpec = if (minimumRemaining > 0) {
                tween(minimumRemaining, easing = FastOutSlowInEasing)
            } else animationSpec,
            visibilityThreshold = 0.0001f,
            label = "glass_progress",
        )
    }
    val latestOnCompleted by rememberUpdatedState(onCompleted)
    val awaitCompletion = target == 1f && onCompleted != null
    LaunchedEffect(animated, awaitCompletion) {
        if (awaitCompletion) {
            // Also handles a result arriving after the last progress report already settled.
            snapshotFlow { animated.value }.first { it >= 0.9999f }
            latestOnCompleted?.invoke()
        }
    }
    val sweep = rememberProgressCycle(running = target == null, durationMillis = 1500)
    GlassProgressTrack(
        progress = { if (target == null) 0.28f else animated.value },
        segmentStart = { if (target == null) sweep.value * 1.28f - 0.28f else 0f },
        color = color,
        modifier = modifier
            .semantics {
                progressBarRangeInfo = target?.let { ProgressBarRangeInfo(it, 0f..1f) }
                    ?: ProgressBarRangeInfo.Indeterminate
            }
            .width(240.dp)
            .height(8.dp),
    )
}

/** A fixed track; animated state is read only during drawing, never during measurement. */
@Composable
internal fun GlassProgressTrack(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = GlassColors.Accent,
    segmentStart: () -> Float = { 0f },
) {
    val currentProgress = rememberUpdatedState(progress)
    val currentStart = rememberUpdatedState(segmentStart)
    Box(
        modifier
            .glassControlShadow(GlassShapes.Capsule, depth = GlassDepths.Low.copy(elevation = 6.dp))
            .clip(GlassShapes.Capsule)
            .background(GlassControlWhite.copy(alpha = controlFillAlpha(GlassTone.Neutral)))
            .glassControlFinish(GlassShapes.Capsule, GlassTone.Neutral, tint = null)
            .drawWithCache {
                // Share the exact button bevel. Thin, frequently updated fills don't need refraction.
                val lighting = GlassControlLighting(GlassTone.Accent, color, 1f, size.height, density)
                val body = color.copy(alpha = color.alpha * controlFillAlpha(GlassTone.Accent))
                val path = Path()
                onDrawBehind {
                    val fraction = currentProgress.value().coerceIn(0f, 1f)
                    val width = size.width * fraction
                    if (width > 0f) {
                        val start = currentStart.value() * size.width
                        val left = if (layoutDirection == LayoutDirection.Ltr) start else size.width - start - width
                        val radius = minOf(width, size.height) / 2f
                        val bounds = RoundRect(left, 0f, left + width, size.height, CornerRadius(radius))
                        val outline = Outline.Rounded(bounds)
                        path.reset()
                        path.addRoundRect(bounds)
                        clipPath(path) {
                            drawOutline(outline, body)
                            lighting.draw(this, outline)
                        }
                    }
                }
            },
    )
}

/** Small loading rings use the same cool body, rounded highlight and colored fill. */
@Composable
fun GlassCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = GlassColors.Accent,
    strokeWidth: Dp = 3.dp,
) {
    val cycle = rememberProgressCycle(running = true, durationMillis = 1500)
    Box(
        modifier
            .progressSemantics()
            .size(40.dp)
            .drawWithCache {
                val width = strokeWidth.toPx().coerceAtLeast(0f).coerceAtMost(size.minDimension / 3f)
                val diameter = (size.minDimension - width * 2).coerceAtLeast(0f)
                val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                val arcSize = Size(diameter, diameter)
                val stroke = Stroke(width, cap = StrokeCap.Round)
                val shine = Stroke(width * 0.28f, cap = StrokeCap.Round)
                val body = Brush.verticalGradient(
                    0f to lerp(color, Color.White, 0.22f),
                    0.45f to color,
                    1f to lerp(color, Color.White, 0.12f),
                )
                val highlight = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.75f),
                    0.5f to Color.White.copy(alpha = 0.04f),
                    1f to Color.White.copy(alpha = 0.42f),
                )
                onDrawBehind {
                    val phase = cycle.value
                    val start = phase * 360f - 90f
                    val sweep = 80f + 170f * ((1f - cos(phase * 2f * PI).toFloat()) / 2f)
                    drawArc(color.copy(alpha = 0.12f), 0f, 360f, false, origin, arcSize, style = stroke)
                    drawArc(
                        Color(0xFF4C5360).copy(alpha = 0.12f), start, sweep, false,
                        origin + Offset(0f, 0.7.dp.toPx()), arcSize, style = stroke,
                    )
                    drawArc(body, start, sweep, false, origin, arcSize, style = stroke)
                    drawArc(highlight, start, sweep, false, origin + Offset(0f, -width * 0.18f), arcSize, style = shine)
                }
            },
    )
}

@Composable
private fun rememberProgressCycle(running: Boolean, durationMillis: Int): Animatable<Float, AnimationVector1D> {
    val cycle = remember { Animatable(0f) }
    val active = running && LocalRelayPageActive.current &&
        !LocalRelayPageWorkPaused.current && isRelayUiStarted()
    LaunchedEffect(active, durationMillis) {
        if (active) {
            cycle.snapTo(0f)
            cycle.animateTo(1f, infiniteRepeatable(tween(durationMillis, easing = LinearEasing)))
        }
    }
    return cycle
}
