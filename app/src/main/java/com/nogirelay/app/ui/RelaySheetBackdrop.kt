package com.nogirelay.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
class RelaySheetBackdropState internal constructor() {
    internal var progress by mutableStateOf<(() -> Float)?>(null)
    val isAttached: Boolean get() = progress != null
    var isSettled by mutableStateOf(false)
        internal set
}

@Composable
fun rememberRelaySheetBackdropState(): RelaySheetBackdropState = remember { RelaySheetBackdropState() }
