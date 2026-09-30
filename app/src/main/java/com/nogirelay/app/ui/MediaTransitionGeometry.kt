package com.nogirelay.app.ui

import kotlin.math.max
import kotlin.math.min

internal data class MediaRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

internal data class MediaFlightGeometry(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
    val clip: MediaRect,
    val radius: Float,
)

/** Transform the actual Fit-rendered viewer into the thumbnail's crop, without swapping images. */
internal fun mediaFlightGeometry(
    source: MediaRect,
    visibleSource: MediaRect,
    viewportWidth: Float,
    viewportHeight: Float,
    aspectRatio: Float,
    cornerRadius: Float,
    progress: Float,
    crop: Boolean = true,
): MediaFlightGeometry {
    val p = progress.coerceIn(0f, 1f)
    val width = viewportWidth.coerceAtLeast(1f)
    val height = viewportHeight.coerceAtLeast(1f)
    val ratio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: width / height
    val fitWidth = min(width, height * ratio)
    val fitHeight = fitWidth / ratio
    val fromScale = if (crop) max(source.width / fitWidth, source.height / fitHeight)
        else min(source.width / fitWidth, source.height / fitHeight)
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    return MediaFlightGeometry(
        scale = lerp(fromScale, 1f),
        translationX = (source.centerX - width / 2f) * (1f - p),
        translationY = (source.centerY - height / 2f) * (1f - p),
        clip = MediaRect(
            lerp(visibleSource.left, 0f), lerp(visibleSource.top, 0f),
            lerp(visibleSource.right, width), lerp(visibleSource.bottom, height),
        ),
        radius = cornerRadius * (1f - p),
    )
}
