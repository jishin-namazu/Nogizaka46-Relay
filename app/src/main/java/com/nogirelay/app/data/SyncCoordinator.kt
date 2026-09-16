package com.nogirelay.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes cold-start, manual, push, and lifecycle sync requests into one database writer. */
class SyncCoordinator {
    private val mutex = Mutex()

    suspend fun <T> synchronize(block: suspend () -> T): T = mutex.withLock { block() }
}
