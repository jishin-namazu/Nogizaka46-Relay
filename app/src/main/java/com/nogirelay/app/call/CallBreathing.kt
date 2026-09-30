package com.nogirelay.app.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

@Composable
internal fun rememberCallBreathing(isRinging: Boolean, visualActive: Boolean): State<Float> {
    val breath = remember { Animatable(0f) }
    LaunchedEffect(isRinging, visualActive) {
        if (!visualActive) {
            breath.snapTo(0f)
            return@LaunchedEffect
        }
        val durationScale = coroutineContext[MotionDurationScale]
        snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest { scale ->
            when {
                scale <= 0f -> breath.snapTo(0f)
                !isRinging -> breath.animateTo(0f, tween(450))
                else -> {
                    val easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
                    while (currentCoroutineContext().isActive) {
                        breath.animateTo(1f, tween(2_100, easing = easing))
                        breath.animateTo(0f, tween(2_700, easing = easing))
                    }
                }
            }
        }
    }
    return breath.asState()
}
