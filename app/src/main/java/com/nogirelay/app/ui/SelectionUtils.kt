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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
