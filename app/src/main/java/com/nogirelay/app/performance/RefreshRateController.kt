package com.nogirelay.app.performance

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import android.view.Window
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Asks every resumed window for the display's highest refresh rate at its
 * current resolution, unless the activity opts into another policy. Battery
 * saver hands the choice back to the system and is re-evaluated live.
 */
class RefreshRateController(context: Context) : DisplayManager.DisplayListener {
    private val appContext = context.applicationContext
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var resumedActivity = WeakReference<Activity>(null)
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            resumedActivity.get()?.let(::apply)
        }
    }

    fun start() {
        displayManager.registerDisplayListener(this, null)
        ContextCompat.registerReceiver(
            appContext,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun apply(activity: Activity) {
        resumedActivity = WeakReference(activity)
        val requestedPolicy = (activity as? RefreshRatePolicyOwner)?.refreshRatePolicy()
            ?: RefreshRatePolicy.Maximum
        val policy = if (powerManager.isPowerSaveMode) RefreshRatePolicy.FollowSystem else requestedPolicy
        applyTo(activity.window, policy)
    }

    fun clear(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity.clear()
    }

    override fun onDisplayAdded(displayId: Int) = reapply(displayId)
    override fun onDisplayChanged(displayId: Int) = reapply(displayId)
    override fun onDisplayRemoved(displayId: Int) = Unit

    private fun reapply(displayId: Int) {
        val activity = resumedActivity.get() ?: return
        if (activity.window.decorView.display?.displayId == displayId) apply(activity)
    }

    companion object {
        /**
         * Applies [policy] to [window]. Dialog and sheet windows call this too:
         * they are separate windows, and one without a preference can pull
         * the whole screen back to the default rate while it is shown.
         */
        fun applyTo(window: Window, policy: RefreshRatePolicy) {
            val display = window.decorView.display ?: return
            val attributes = window.attributes
            var modeId = 0
            val rate = when (policy) {
                RefreshRatePolicy.FollowSystem -> 0f
                RefreshRatePolicy.Maximum -> {
                    // Many vendors ignore a bare rate hint but honor an
                    // explicit display mode, so request both.
                    val mode = maximumModeAtCurrentResolution(display)
                    modeId = mode?.modeId ?: 0
                    mode?.refreshRate ?: 0f
                }
                is RefreshRatePolicy.Fixed -> policy.fps.coerceAtLeast(0f)
                is RefreshRatePolicy.Video -> policy.sourceFps.coerceAtLeast(0f)
            }
            if (attributes.preferredRefreshRate != rate || attributes.preferredDisplayModeId != modeId) {
                attributes.preferredRefreshRate = rate
                attributes.preferredDisplayModeId = modeId
                window.attributes = attributes
            }
        }

        /** [RefreshRatePolicy.Maximum], or the system's choice in battery saver. */
        fun applyMaximum(window: Window) {
            val powerSave = window.context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            applyTo(window, if (powerSave) RefreshRatePolicy.FollowSystem else RefreshRatePolicy.Maximum)
        }

        private fun maximumModeAtCurrentResolution(display: Display): Display.Mode? {
            val current = display.mode
            return display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
        }
    }
}
