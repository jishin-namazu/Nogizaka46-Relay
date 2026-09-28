package com.nogirelay.app.call

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings

object OverlayPermission {
    fun canUse(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun settingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = "package:${context.packageName}".toUri()
        }
    }
}
