package com.nogirelay.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Automatically clears text selection and dismisses the floating toolbar
 * when the user taps anywhere on the modified container, while preserving:
 * 1. Long press gestures for text selection (duration >= longPressTimeout).
 * 2. Drag / scroll gestures (distance > touchSlop).
 * 3. Normal clicks on children (events are NOT consumed).
 */
fun Modifier.clearSelectionOnTap(
    focusManager: FocusManager,
    textToolbar: TextToolbar? = null,
): Modifier = pointerInput(focusManager, textToolbar) {
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startTime = System.currentTimeMillis()
        var movedBeyondSlop = false

        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val currentChange = event.changes.firstOrNull { it.id == down.id } ?: break

            if (!currentChange.pressed) {
                val duration = System.currentTimeMillis() - startTime
                if (!movedBeyondSlop && duration < longPressTimeout) {
                    focusManager.clearFocus()
                    textToolbar?.hide()
                }
                break
            }

            val distance = (currentChange.position - down.position).getDistance()
            if (distance > touchSlop) {
                movedBeyondSlop = true
            }
        }
    }
}

/**
 * Automatically clears selection and hides the copy toolbar when exiting the screen:
 * - When [isActive] becomes false (e.g. switching tabs).
 * - When the composable leaves the composition (e.g. back navigation).
 * - When the activity enters [Lifecycle.Event.ON_PAUSE] or [Lifecycle.Event.ON_STOP] (e.g. leaving the app or opening another activity).
 */
@Composable
fun AutoClearSelectionOnExit(
    isActive: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(isActive) {
        if (!isActive) {
            focusManager.clearFocus()
            textToolbar.hide()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            focusManager.clearFocus()
            textToolbar.hide()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                focusManager.clearFocus()
                textToolbar.hide()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
