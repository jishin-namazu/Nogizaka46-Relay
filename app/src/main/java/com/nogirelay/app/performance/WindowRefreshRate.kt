package com.nogirelay.app.performance

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Requests the highest refresh rate for the dialog or sheet window hosting
 * this composition. Activities are handled by [RefreshRateController]; each
 * dialog is its own window and otherwise has no preference.
 */
@Composable
fun MaximumRefreshRateForDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window: Window? = generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
            ?.window
        if (window != null) {
            val decor = window.decorView
            if (decor.isAttachedToWindow) {
                RefreshRateController.applyMaximum(window)
            } else {
                decor.post { RefreshRateController.applyMaximum(window) }
            }
        }
        onDispose { }
    }
}
