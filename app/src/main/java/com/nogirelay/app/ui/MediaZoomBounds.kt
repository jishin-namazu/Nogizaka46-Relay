package com.nogirelay.app.ui

internal data class MediaOffset(val x: Float, val y: Float) {
    companion object {
        val Zero = MediaOffset(0f, 0f)
    }
}

/** Converts a gesture delta from the transformed content's coordinates to screen pixels. */
internal fun contentPanToScreen(x: Float, y: Float, scale: Float): MediaOffset {
    val safeScale = scale.coerceAtLeast(1f)
    return MediaOffset(x * safeScale, y * safeScale)
}

/**
 * Keeps a scaled, viewport-sized media layer covering the viewport on both axes.
 * At 1x there is no pannable overflow, while every extra scaled pixel can be panned into view.
 */
internal fun constrainMediaOffset(
    x: Float,
    y: Float,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): MediaOffset {
    if (scale <= 1f || viewportWidth <= 0f || viewportHeight <= 0f) return MediaOffset.Zero

    val maxX = viewportWidth * (scale - 1f) / 2f
    val maxY = viewportHeight * (scale - 1f) / 2f
    return MediaOffset(
        x = x.coerceIn(-maxX, maxX),
        y = y.coerceIn(-maxY, maxY),
    )
}
