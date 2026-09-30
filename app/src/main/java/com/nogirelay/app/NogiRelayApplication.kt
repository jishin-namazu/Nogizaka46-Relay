package com.nogirelay.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.performance.RefreshRateController
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeFeatureFlags

class NogiRelayApplication : Application(), Application.ActivityLifecycleCallbacks {
    private lateinit var refreshRateController: RefreshRateController

    @OptIn(ExperimentalHazeApi::class)
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }

        HazeFeatureFlags.isPlatformBackdropEnabled = true
        AppGraph.initialize(this)
        refreshRateController = RefreshRateController(this).also { it.start() }
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) = refreshRateController.apply(activity)
    override fun onActivityPaused(activity: Activity) = refreshRateController.clear(activity)
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) com.nogirelay.app.ui.RemoteImageMemoryCache.trim(clear = true)
        else if (level >= TRIM_MEMORY_RUNNING_LOW) com.nogirelay.app.ui.RemoteImageMemoryCache.trim(clear = false)
    }
}
