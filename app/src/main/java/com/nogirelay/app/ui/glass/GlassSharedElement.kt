package com.nogirelay.app.ui.glass

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect

internal val LocalMediaSourceScope = staticCompositionLocalOf { "messages" }

internal fun mediaSourceKey(scope: String, messageId: String) = "$scope:$messageId"

/**
 * Thumbnail-to-viewer shared element plumbing.
 *
 * The media viewer runs in its own Activity, so framework shared-element
 * transitions do not apply. Instead, thumbnails continuously publish their
 * on-screen geometry here; the viewer Activity reads it on launch, renders
 * the same image at the exact source rect on a transparent window, and
 * springs it to fullscreen. On close it morphs back to the (still visible)
 * source, or falls back to a smooth fade+scale when the source scrolled
 * away. The user perceives one persistent object being lifted out of the
 * page and returned to it.
 */
object GlassMediaTransition {

    class Source(
        val url: String,
        val bounds: IntRect,
        val cornerRadiusPx: Float,
        val visibleBounds: IntRect = bounds,
        val crop: Boolean = true,
        internal val owner: Any,
    )

    private val sources = mutableStateMapOf<String, Source>()

    /** Called by thumbnails while they are laid out and on screen. */
    fun publish(key: String, url: String, bounds: IntRect, cornerRadiusPx: Float, visibleBounds: IntRect, crop: Boolean, owner: Any) {
        sources[key] = Source(url, bounds, cornerRadiusPx, visibleBounds, crop, owner)
    }

    fun unpublish(key: String, owner: Any) {
        if (sources[key]?.owner === owner) sources.remove(key)
    }

    /**
     * Latest source geometry for [key] if its thumbnail is still on screen.
     * Returns null when the source scrolled away (caller should fall back to
     * fade + scale).
     */
    fun visibleSource(key: String): Source? =
        sources[key]?.takeIf { it.visibleBounds.width > 0 && it.visibleBounds.height > 0 }

}

/**
 * Tracks this composable as a shared-element media source. [key] must be
 * stable and unique per screen and media item.
 */
fun Modifier.glassMediaSource(
    key: String?,
    url: String?,
    cornerRadiusPx: Float,
    crop: Boolean = true,
): Modifier {
    if (key.isNullOrEmpty() || url.isNullOrEmpty()) return this
    return composed {
        val radius = rememberUpdatedState(cornerRadiusPx)
        val owner = remember(key) { Any() }
        DisposableEffect(key) {
            onDispose { GlassMediaTransition.unpublish(key, owner) }
        }
        onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            val position = coords.localToWindow(Offset.Zero)
            val end = coords.localToWindow(Offset(coords.size.width.toFloat(), coords.size.height.toFloat()))
            GlassMediaTransition.publish(
                key = key,
                url = url,
                bounds = IntRect(
                    left = position.x.toInt(),
                    top = position.y.toInt(),
                    right = end.x.toInt(),
                    bottom = end.y.toInt(),
                ),
                visibleBounds = IntRect(
                    left = b.left.toInt(),
                    top = b.top.toInt(),
                    right = b.right.toInt(),
                    bottom = b.bottom.toInt(),
                ),
                cornerRadiusPx = radius.value,
                crop = crop,
                owner = owner,
            )
        }
    }
}

