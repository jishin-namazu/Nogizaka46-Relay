package com.nogirelay.app.data

import android.content.Context
import com.nogirelay.app.performance.PerformanceDispatchers

object AppGraph {
    @Volatile
    private var initialized = false

    lateinit var settings: SettingsStore
        private set
    lateinit var database: MessageDatabase
        private set
    lateinit var relayClient: RelayClient
        private set
    lateinit var blogClient: BlogClient
        private set
    lateinit var dispatchers: PerformanceDispatchers
        private set
    lateinit var blogRepository: BlogRepository
        private set
    val syncCoordinator = SyncCoordinator()
    private val _dataVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val dataVersion: kotlinx.coroutines.flow.StateFlow<Long> = _dataVersion

    fun notifyDataChanged() {
        _dataVersion.value += 1
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            settings = SettingsStore(appContext)
            database = MessageDatabase(appContext)
            relayClient = RelayClient()
            blogClient = BlogClient()
            dispatchers = PerformanceDispatchers()
            blogRepository = BlogRepository(database, settings, dispatchers)
            initialized = true
        }
    }
}
