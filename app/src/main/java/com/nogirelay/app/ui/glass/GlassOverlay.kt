package com.nogirelay.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nogirelay.app.performance.LocalRelayPageWorkPaused
import com.nogirelay.app.ui.RelaySheetBackdropState
import kotlinx.coroutines.launch

/**
 * Glass overlays: dialogs and bottom sheets. Both keep the "objects persist"
 * rule — a dialog grows from a slightly compressed, sunken state on a damped
 * spring; sheets slide on the platform springs and restyle as glass.
 */

@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val appear = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, GlassMotion.MorphSpec) }
        // The backdrop shares the card's bounds and clip, leaving the area
        // outside its rounded corners transparent in the dialog window.
        Box(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .padding(24.dp)
                .graphicsLayer {
                    val p = appear.value
                    alpha = 0.3f + 0.7f * p
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                    translationY = (1f - p) * 18.dp.toPx()
                }
                .glassShadow(GlassShapes.CardLarge, GlassDepths.High)
                .clip(GlassShapes.CardLarge),
        ) {
            GlassBackdrop(
                background = SolidColor(GlassColors.SheetSurface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassPanel(
                    shape = GlassShapes.CardLarge,
                    depth = GlassDepths.None,
                    fillAlpha = GlassColors.NeutralFillStrongAlpha,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), content = content)
                }
            }
        }
    }
}

@Composable
fun GlassDialogTitle(text: String) {
    Text(
        text = text,
        color = GlassColors.Ink,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun GlassDialogText(text: String) {
    Text(
        text = text,
        color = GlassColors.InkSecondary,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

/**
 * Bottom sheet: same dismiss-coordination contract as the legacy sheet, but
 * the container is one large rounded slab of liquid glass with a pill
 * grabber, and the content area hosts its own haze backdrop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    backdropState: RelaySheetBackdropState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(dismiss: (afterHidden: () -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    var closing by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var afterHidden by remember { mutableStateOf<(() -> Unit)?>(null) }
    var fullHeight by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var sheetHeight by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    DisposableEffect(backdropState, sheetState) {
        val progress = {
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
        if (closing || dismissed) return
        closing = true
        afterHidden = action
        scope.launch {
            try {
                sheetState.hide()
                completeDismiss()
            } finally {
                if (!dismissed) {
                    closing = false
                    afterHidden = null
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::completeDismiss,
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            fullHeight = constraints.maxHeight.toFloat()
            sheetHeight = placeable.height.toFloat()
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
        sheetState = sheetState,
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.5.dp)
                        .background(
                            GlassColors.Ink.copy(alpha = 0.18f),
                            GlassShapes.Capsule,
                        ),
                )
            }
        },
        // Paint the entire rounded sheet, including the native drag-handle slot.
        containerColor = GlassColors.SheetSurface,
        contentColor = GlassColors.Ink,
        tonalElevation = 0.dp,
        scrimColor = Color(0x33090A10),
        shape = GlassShapes.Sheet,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalRelayPageWorkPaused provides false) {
            GlassBackdrop(
                background = SolidColor(GlassColors.SheetSurface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) { content(::dismiss) }
            }
        }
    }
}


