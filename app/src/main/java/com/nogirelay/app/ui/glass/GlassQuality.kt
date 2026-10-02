package com.nogirelay.app.ui.glass

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * How much of the glass effect the device should render right now.
 *
 * Blur and refraction are the expensive part of the material; they are turned
 * off on low-RAM devices, in battery saver and while the device is thermally
 * throttled, and surfaces fall back to their dense translucent fills.
 * Decorative motion (pulses, container morphs, page settles) is reduced when
 * the system removes animations or battery saver is on.
 */
@Immutable
data class GlassQuality(
    val blur: Boolean = true,
    val reducedMotion: Boolean = false,
)

/** True when decorative motion should be skipped or shortened. */
val LocalGlassReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberGlassQuality(): GlassQuality {
    val context = LocalContext.current.applicationContext
    var signals by remember { mutableStateOf(GlassQualitySignals.read(context)) }

    DisposableEffect(context) {
        val refresh = { signals = GlassQualitySignals.read(context) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
            PowerManager.OnThermalStatusChangedListener { refresh() }
                .also { power.addThermalStatusListener(ContextCompat.getMainExecutor(context), it) }
        } else {
            null
        }
        onDispose {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermal != null) {
                power?.removeThermalStatusListener(thermal)
            }
        }
    }

    return remember(signals) {
        GlassQuality(
            blur = !signals.lowRam && !signals.powerSave && !signals.throttled,
            reducedMotion = signals.animationsOff || signals.powerSave,
        )
    }
}

private data class GlassQualitySignals(
    val lowRam: Boolean,
    val powerSave: Boolean,
    val throttled: Boolean,
    val animationsOff: Boolean,
) {
    companion object {
        fun read(context: Context): GlassQualitySignals {
            val power = context.getSystemService(PowerManager::class.java)
            val activity = context.getSystemService(ActivityManager::class.java)
            val throttled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                power != null &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            val animatorScale = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            return GlassQualitySignals(
                lowRam = activity?.isLowRamDevice == true,
                powerSave = power?.isPowerSaveMode == true,
                throttled = throttled,
                animationsOff = animatorScale == 0f,
            )
        }
    }
}

/** A haze source that stops recording its content while blur is disabled. */
@Composable
fun Modifier.glassHazeSource(state: HazeState): Modifier =
    if (LocalGlassBlurEnabled.current) hazeSource(state) else this
