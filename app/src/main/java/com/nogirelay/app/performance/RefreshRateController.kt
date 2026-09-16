package com.nogirelay.app.performance

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import java.lang.ref.WeakReference

class RefreshRateController(context: Context) : DisplayManager.DisplayListener {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var resumedActivity = WeakReference<Activity>(null)

    fun start() {
        displayManager.registerDisplayListener(this, null)
    }

    fun apply(activity: Activity) {
        resumedActivity = WeakReference(activity)
        val requestedPolicy = (activity as? RefreshRatePolicyOwner)?.refreshRatePolicy()
            ?: RefreshRatePolicy.Maximum
        apply(activity, if (powerManager.isPowerSaveMode) RefreshRatePolicy.FollowSystem else requestedPolicy)
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

    private fun apply(activity: Activity, policy: RefreshRatePolicy) {
        val display = activity.window.decorView.display ?: return
        val requestedRate = when (policy) {
            RefreshRatePolicy.FollowSystem -> 0f
            RefreshRatePolicy.Maximum -> maximumRateAtCurrentResolution(display)
            is RefreshRatePolicy.Fixed -> policy.fps.coerceAtLeast(0f)
            is RefreshRatePolicy.Video -> policy.sourceFps.coerceAtLeast(0f)
        }
        val attributes = activity.window.attributes
        if (attributes.preferredRefreshRate != requestedRate) {
            attributes.preferredRefreshRate = requestedRate
            activity.window.attributes = attributes
        }
    }

    private fun maximumRateAtCurrentResolution(display: Display): Float {
        val current = display.mode
        return display.supportedModes
            .asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxOfOrNull { it.refreshRate }
            ?: 0f
    }
}
