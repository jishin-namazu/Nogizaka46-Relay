package com.nogirelay.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `media_refs` 表的后台重建器。
 *
 * 引用表在写入记录时增量维护，两种情况整体重建：
 *  1. 升级到带引用表的版本时；
 *  2. [MediaRefs.PARSE_VERSION] 提升后。
 *
 * 重建单飞、跑在后台；期间 [MessageDatabase.mediaRefsReady] 为 false，统计与导出走直接解析。
 */
object MediaRefIndex {
    private const val TAG = "NogiMediaRefs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /** 版本不匹配且当前没有重建在跑时，启动一次后台重建。 */
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
