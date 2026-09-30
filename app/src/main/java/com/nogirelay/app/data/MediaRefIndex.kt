package com.nogirelay.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object MediaRefIndex {
    private const val TAG = "NogiMediaRefs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    fun ensureBuilt(database: MessageDatabase) {
        if (database.mediaRefsReady()) return
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                database.rebuildMediaRefs()
                database.markMediaRefsReady()
                Log.d(TAG, "media refs rebuilt at parse version " + MediaRefs.PARSE_VERSION)
            } catch (error: Throwable) {
                Log.w(TAG, "media refs rebuild failed", error)
            } finally {
                running.set(false)
            }
        }
    }
}
