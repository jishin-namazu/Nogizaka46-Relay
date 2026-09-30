package com.nogirelay.app.ui.glass

import androidx.compose.runtime.LongState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/**
 * Scroll-driven invalidation tick shared by the app shell: scrolling a page
 * bumps [LocalGlassHazeDrawTick], which glass surfaces observe so backdrop
 * sampling stays in sync with fast flings.
 */
val LocalGlassHazeDrawTick = staticCompositionLocalOf<MutableLongState?> { null }

fun Modifier.glassHazeSourceTick(tick: MutableLongState): Modifier = drawWithContent {
    drawContent()
    tick.longValue++
}

fun Modifier.glassHazeEffectTick(tick: LongState): Modifier = drawWithContent {
    tick.longValue
    drawContent()
}
