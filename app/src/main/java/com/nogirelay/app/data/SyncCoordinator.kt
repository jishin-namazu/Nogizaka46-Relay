package com.nogirelay.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 将冷启动、手动、推送和生命周期同步请求串行到同一个数据库写入者。 */
class SyncCoordinator {
    private val mutex = Mutex()

    suspend fun <T> synchronize(block: suspend () -> T): T = mutex.withLock { block() }
}
