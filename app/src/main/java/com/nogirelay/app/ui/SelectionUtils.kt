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
 * 当用户点击被修饰容器的任意位置时，自动清除文本选择并关闭浮动工具栏，
 * 同时保留：
 * 1. 用于文本选择的长按手势（时长 >= longPressTimeout）。
 * 2. 拖拽 / 滚动手势（距离 > touchSlop）。
 * 3. 对子元素的普通点击（事件不会被消费）。
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
 * 离开界面时自动清除选择并隐藏复制工具栏：
 * - 当 [isActive] 变为 false 时（例如切换标签页）。
 * - 当该 composable 离开组合时（例如返回导航）。
 * - 当 Activity 进入 [Lifecycle.Event.ON_PAUSE] 或 [Lifecycle.Event.ON_STOP] 时（例如离开应用或打开另一个 Activity）。
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
