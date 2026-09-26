package com.nogirelay.app.ui

internal data class MediaOffset(val x: Float, val y: Float) {
    companion object {
        val Zero = MediaOffset(0f, 0f)
    }
}

/** 把手势增量从变换后内容的坐标转换为屏幕像素。 */
internal fun contentPanToScreen(x: Float, y: Float, scale: Float): MediaOffset {
    val safeScale = scale.coerceAtLeast(1f)
    return MediaOffset(x * safeScale, y * safeScale)
}

/**
 * 让经过缩放、视口大小的媒体层在两个轴上都覆盖视口。
 * 在 1x 时没有可平移的溢出，而每一个额外缩放出的像素都能被平移进视野。
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
