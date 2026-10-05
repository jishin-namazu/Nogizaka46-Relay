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

    fun ensureBuilt(refs: com.nogirelay.app.data.db.MediaRefDao) {
        if (!running.compareAndSet(false, true)) return
        // Even the readiness check opens (and may migrate) the database: keep it off the caller's thread.
        scope.launch {
            try {
                if (refs.mediaRefsReady()) return@launch
                refs.rebuildMediaRefs()
                refs.markMediaRefsReady()
                Log.d(TAG, "media refs rebuilt at parse version " + MediaRefs.PARSE_VERSION)
            } catch (error: Throwable) {
                Log.w(TAG, "media refs rebuild failed", error)
            } finally {
                running.set(false)
            }
        }
    }
}
