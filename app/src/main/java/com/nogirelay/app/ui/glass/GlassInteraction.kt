package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Soft-body press: the surface does not merely scale down, it yields like a
 * pad of clear gel — vertical squash is stronger than horizontal, the body
 * sinks a little, the shadow compresses, and the whole thing (glass + icon +
 * label) moves as one object. Release is a damped spring with a whisper of
 * overshoot.
 */
@Stable
class GlassPress internal constructor(
    private val progressState: State<Float>,
) {
    /** 0 = resting, 1 = fully pressed. */
    val progress: Float get() = progressState.value

    val scaleX: Float get() = 1f - 0.026f * progress
    val scaleY: Float get() = 1f - 0.058f * progress
    val sinkDp: Dp get() = (1.4f * progress).dp

    /** Shadow pulls in and weakens while pressed. */
    val shadowFactor: Float get() = 1f - 0.45f * progress
}

@Composable
fun rememberGlassPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
): GlassPress {
    val pressed by interactionSource.collectIsPressedAsState()
    val anim = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(pressed, enabled) {
        val target = if (pressed && enabled) 1f else 0f
        anim.animateTo(
            target,
            if (target > anim.value) GlassMotion.PressInSpec else GlassMotion.ReleaseSpec,
        )
    }
    return remember(interactionSource) {
        GlassPress(progressState = object : State<Float> {
            override val value: Float get() = anim.value
        })
    }
}

/**
 * Applies the press deformation + a live-compressing ambient shadow.
 * Place BEFORE [glass]/content modifiers so everything moves as one body.
 */
fun Modifier.glassPress(
    press: GlassPress,
    shape: Shape,
    depth: GlassDepth,
): Modifier = graphicsLayer {
    scaleX = press.scaleX
    scaleY = press.scaleY
    translationY = press.sinkDp.toPx()
    if (depth.elevation > 0.dp) {
        shadowElevation = (depth.elevation * press.shadowFactor).toPx()
        this.shape = shape
        clip = false
        ambientShadowColor = depth.ambient
        spotShadowColor = depth.spot
    }
}

/** Plain deformation without a shadow (for surfaces that already cast one). */
fun Modifier.glassPress(press: GlassPress): Modifier = graphicsLayer {
    scaleX = press.scaleX
    scaleY = press.scaleY
    translationY = press.sinkDp.toPx()
}

// ---------------------------------------------------------------------------
// Viscous elastic drag
// ---------------------------------------------------------------------------

/**
 * Spring-follow drag controller: the finger leads, the liquid body follows
 * through a stiff-but-not-rigid spring, and the trailing edge stretches with
 * velocity. On release the body settles with a damped spring (tiny
 * overshoot). Used by sliders / scrubbers / draggable glass.
 */
@Stable
class GlassViscousDrag internal constructor(
    private val scope: CoroutineScope,
    initial: Float,
    private val range: ClosedFloatingPointRange<Float>,
    private val onValueChange: (Float) -> Unit,
) {
    private val anim = Animatable(initial.coerceIn(range.start, range.endInclusive))

    var dragging by mutableStateOf(false)
        private set

    /** Current visual position of the liquid body (may trail the finger). */
    val position: Float get() = anim.value

    /** Signed velocity of the body in value-units per second. */
    val velocity: Float get() = anim.velocity

    /**
     * Stretch factor along the drag axis, derived from velocity. 1 = resting
     * capsule; higher = elongated in the direction of travel.
     */
    val stretch: Float
        get() {
            val span = (range.endInclusive - range.start).coerceAtLeast(0.0001f)
            val normalized = (velocity / span).coerceIn(-6f, 6f)
            return 1f + kotlin.math.min(kotlin.math.abs(normalized) * 0.10f, 0.32f)
        }

    fun dragTo(fingerValue: Float) {
        val clamped = fingerValue.coerceIn(range.start, range.endInclusive)
        onValueChange(clamped)
        scope.launch { anim.animateTo(clamped, GlassMotion.DragFollowSpec) }
    }

    fun snapTo(value: Float) {
        val clamped = value.coerceIn(range.start, range.endInclusive)
        scope.launch { anim.snapTo(clamped) }
    }

    fun beginDrag() {
        dragging = true
    }

    /** Release with magnetic snap toward [snapTarget] when close enough. */
    fun release(
        snapTargets: List<Float> = listOf(range.start, range.endInclusive),
        snapThresholdFraction: Float = 0.04f,
        onSettled: ((Float) -> Unit)? = null,
    ) {
        dragging = false
        val span = (range.endInclusive - range.start).coerceAtLeast(0.0001f)
        val current = anim.value
        val snapped = snapTargets.minByOrNull { kotlin.math.abs(it - current) }
            ?.takeIf { kotlin.math.abs(it - current) / span <= snapThresholdFraction }
        val target = snapped ?: current
        scope.launch {
            if (snapped != null) onValueChange(target)
            anim.animateTo(target, GlassMotion.SnapSpec)
            onSettled?.invoke(target)
        }
    }
}

@Composable
fun rememberGlassViscousDrag(
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
): GlassViscousDrag {
    val scope = rememberCoroutineScope()
    return remember(scope, range) { GlassViscousDrag(scope, initial, range, onValueChange) }
}
