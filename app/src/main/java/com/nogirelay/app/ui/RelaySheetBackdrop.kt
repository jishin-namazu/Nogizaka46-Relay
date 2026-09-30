package com.nogirelay.app.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Stable
class RelaySheetBackdropState internal constructor() {
    internal var progress by mutableStateOf<(() -> Float)?>(null)
    val isAttached: Boolean get() = progress != null
    var isSettled by mutableStateOf(false)
        internal set
}

@Composable
fun rememberRelaySheetBackdropState(): RelaySheetBackdropState = remember { RelaySheetBackdropState() }

@Composable
fun Modifier.relaySheetBackdrop(state: RelaySheetBackdropState): Modifier {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !state.isAttached) return this

    val contentLayer = rememberGraphicsLayer()
    val blurredLayer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { 18.dp.toPx() }
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    val background = MaterialTheme.colorScheme.background

    return drawWithCache {
        blurredLayer.renderEffect = blur
        var blurRecorded = false
        onDrawWithContent {

            drawContent()
            val progress = state.progress?.invoke()?.coerceIn(0f, 1f) ?: 0f
            if (progress > 0f) {
                if (!blurRecorded) {
                    blurredLayer.record { drawLayer(contentLayer) }
                    blurRecorded = true
                }
                blurredLayer.alpha = progress
                drawLayer(blurredLayer)
            }
        }
    }.graphicsLayer().drawWithContent {
        contentLayer.record {

            drawRect(background)
            this@drawWithContent.drawContent()
        }
        drawLayer(contentLayer)
    }
}
