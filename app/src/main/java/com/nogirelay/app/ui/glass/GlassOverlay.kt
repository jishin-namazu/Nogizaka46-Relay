package com.nogirelay.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss as dismissSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
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
    frostedBackground: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(22.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val overlayLayer = rememberGlassOverlayLayer(minimumLevel = 2f)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val appear = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, GlassMotion.MorphSpec) }
        // The backdrop shares the card's bounds and clip, leaving the area
        // outside its rounded corners transparent in the dialog window.
        Box(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .graphicsLayer {
                    val p = appear.value.coerceIn(0f, 1f)
                    alpha = 0.3f + 0.7f * p
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                    translationY = (1f - p) * 18.dp.toPx()
                }
                .glassControlShadow(GlassShapes.CardLarge, depth = GlassDepths.High)
                .then(
                    if (frostedBackground) {
                        Modifier.glassOverlaySurface(overlayLayer, GlassShapes.CardLarge, fillAlpha = 0.56f)
                    } else {
                        Modifier.glassOverlaySource(overlayLayer)
                            .clip(GlassShapes.CardLarge)
                            .background(GlassColors.SheetSurface)
                    },
                ),
        ) {
            CompositionLocalProvider(LocalGlassOverlayLevel provides overlayLayer.level) {
                Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
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
 * Bottom sheet with an opaque, rounded background. It still publishes its
 * contents as a source for the frosted selection menus opened above it.
 * Place the supplied handle inside the content's scroll container, and keep
 * navigation-bar spacing at the end of that content so the viewport stays full-height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    backdropState: RelaySheetBackdropState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(
        dismiss: (afterHidden: () -> Unit) -> Unit,
        handle: @Composable () -> Unit,
    ) -> Unit,
) {
    val overlayLayer = rememberGlassOverlayLayer(minimumLevel = 1f)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val popoverViewport = remember { GlassPopoverViewport() }
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
        // Material positions the sheet after this modifier. A clip or recorded
        // blur layer here would remain at the unshifted origin and cut it off.
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            fullHeight = constraints.maxHeight.toFloat()
            sheetHeight = placeable.height.toFloat()
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
        sheetState = sheetState,
        // The handle scrolls with the content inside Material's moving surface.
        dragHandle = null,
        // Content adds its own trailing safe area inside the scroll container.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        containerColor = GlassColors.SheetSurface,
        contentColor = GlassColors.Ink,
        tonalElevation = 0.dp,
        scrimColor = Color(0x33090A10),
        shape = GlassShapes.Sheet,
    ) {
        CompositionLocalProvider(
            LocalRelayPageWorkPaused provides false,
            LocalGlassOverlayLevel provides overlayLayer.level,
            LocalGlassPopoverViewport provides popoverViewport,
        ) {
            val topSafeInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
            Column(
                Modifier.fillMaxWidth()
                    // Cap the content inside the moving surface. Material must still
                    // measure its anchors against the full window to leave this gap above it.
                    .layout { measurable, constraints ->
                        val topClearance = topSafeInsets.getTop(this) + 16.dp.roundToPx()
                        val maxSheetHeight = (constraints.maxHeight - topClearance).coerceAtLeast(0)
                        val placeable = measurable.measure(
                            constraints.copy(
                                minHeight = constraints.minHeight.coerceAtMost(maxSheetHeight),
                                maxHeight = maxSheetHeight,
                            ),
                        )
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        popoverViewport.bounds = IntRect(
                            bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt(),
                        )
                    }
                    .glassOverlaySource(overlayLayer)
                    .background(GlassColors.SheetSurface),
            ) {
                content(::dismiss) {
                    Box(
                        Modifier.fillMaxWidth().height(40.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                enabled = !closing && !dismissed,
                                onClick = { dismiss(latestOnDismiss) },
                            )
                            .semantics {
                                contentDescription = "关闭抽屉"
                                dismissSemantics { dismiss(latestOnDismiss); true }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.width(40.dp).height(4.5.dp)
                                .background(GlassColors.Ink.copy(alpha = 0.18f), GlassShapes.Capsule),
                        )
                    }
                }
            }
        }
    }
}


