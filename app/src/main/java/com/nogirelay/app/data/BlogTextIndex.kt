package com.nogirelay.app.data

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fills `blog_posts.body_text` for posts stored before it existed, so search
 * reads plain text instead of HTML. Posts without it are still searched by
 * their HTML meanwhile.
 */
object BlogTextIndex {
    private const val TAG = "NogiBlogText"
    private const val BATCH = 100
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    fun ensureBuilt() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                var filled = 0
                while (true) {
                    val batch = AppGraph.blogs.postsWithoutBodyText(BATCH)
                    if (batch.isEmpty()) break
                    AppGraph.database.transaction {
                        batch.forEach { (id, html) -> AppGraph.blogs.saveBodyText(id, html) }
                    }
                    filled += batch.size
                    if (batch.size < BATCH) break
                }
                if (filled > 0) Log.d(TAG, "Stored plain text for $filled posts")
            } catch (error: Throwable) {
                Log.w(TAG, "Blog text backfill failed", error)
            } finally {
                running.set(false)
            }
        }
    }
}
