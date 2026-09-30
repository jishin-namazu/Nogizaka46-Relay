package com.nogirelay.app.performance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

val LocalRelayPageActive = staticCompositionLocalOf { true }
val LocalRelayPageWorkPaused = compositionLocalOf { false }

@Composable
fun isRelayUiStarted(): Boolean =
    LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)

@Composable
fun rememberSearchQuery(input: String, active: Boolean): String {
    var settled by remember { mutableStateOf(input) }
    LaunchedEffect(input, active) {
        if (input.isBlank()) settled = ""
        else if (active) {
            delay(275)
            settled = input
        }
    }
    return if (input.isBlank()) "" else settled
}
