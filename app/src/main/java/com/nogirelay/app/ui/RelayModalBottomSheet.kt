package com.nogirelay.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.nogirelay.app.performance.LocalRelayPageWorkPaused

/** Shared drawer shell. Buttons use [dismiss]; native dismissal has already hidden the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayModalBottomSheet(
    onDismissRequest: () -> Unit,
    backdropState: RelaySheetBackdropState,
    borderless: Boolean = false,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.(dismiss: (afterHidden: () -> Unit) -> Unit) -> Unit,
) {
    val mirrorStyle = borderless || LocalRelayMirrorStyle.current
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    var closing by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var afterHidden by remember { mutableStateOf<(() -> Unit)?>(null) }
    var fullHeight by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }

    DisposableEffect(backdropState, sheetState) {
        val progress = {
            // These are the same constraints and height used by Material 3's anchors.
            // Reading offset here also follows partial drags and interrupted dismissals.
            val travel = sheetHeight.coerceAtMost(fullHeight)
            if (travel > 0f && sheetState.hasExpandedState) {
                ((fullHeight - sheetState.requireOffset()) / travel).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        backdropState.progress = progress
        onDispose {
            if (backdropState.progress === progress) {
                backdropState.progress = null
                backdropState.isSettled = false
            }
        }
    }

    LaunchedEffect(backdropState, sheetState) {
        snapshotFlow {
            !closing && !dismissed && sheetState.currentValue == SheetValue.Expanded &&
                sheetState.targetValue == SheetValue.Expanded && !sheetState.isAnimationRunning &&
                (backdropState.progress?.invoke() ?: 0f) >= 0.999f
        }.collect { backdropState.isSettled = it }
    }

    fun completeDismiss() {
        if (sheetState.isVisible || dismissed) return
        dismissed = true
        (afterHidden ?: latestOnDismiss)()
    }

    fun dismiss(action: () -> Unit) {
        // Repeated close/confirm taps must not restart the exit or run callbacks twice.
        if (closing || dismissed) return
        closing = true
        afterHidden = action
        scope.launch {
            try {
                sheetState.hide()
                completeDismiss()
            } finally {
                // A drag can cancel hide(). Keep the still-visible drawer usable.
                if (!dismissed) {
                    closing = false
                    afterHidden = null
                }
            }
        }
    }

    ModalBottomSheet(
        // Back, scrim taps and swipe dismissal animate inside Material 3 before this
        // callback. Calling hide() again here adds an unnecessary second exit.
        onDismissRequest = ::completeDismiss,
        // Caller modifiers precede Material 3's anchored offset. Clipping here would cut off
        // the translated sheet; its internal Surface applies shape clipping after that offset.
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            fullHeight = constraints.maxHeight.toFloat()
            sheetHeight = placeable.height.toFloat()
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
        sheetState = sheetState,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        // Keep the solid backdrop and scrolling content inside the same rounded top corners.
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (mirrorStyle) 1f else 0.985f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = if (mirrorStyle) MaterialTheme.colorScheme.surface.copy(alpha = 0.62f) else Color.Black.copy(alpha = 0.34f),
        shape = sheetShape,
    ) {
        // This window stays active while the covered page idles.
        CompositionLocalProvider(LocalRelayPageWorkPaused provides false) {
            if (mirrorStyle) {
                // Modal windows have their own solid, content-free source for glass controls.
                RelayGlassBackdrop(
                    background = SolidColor(MaterialTheme.colorScheme.surface.copy(alpha = 1f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) { content(::dismiss) }
                }
            } else {
                content(::dismiss)
            }
        }
    }
}
