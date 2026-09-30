package com.nogirelay.app.data

import android.content.Context
import com.nogirelay.app.performance.PerformanceDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
    val memberSummaries = MemberSummaryRepository()
    private val _dataVersions = MutableStateFlow(DataVersions())
    val dataVersions: StateFlow<DataVersions> = _dataVersions

    fun notifyDataChanged(change: DataChange = DataChange.ALL, ids: Set<String> = emptySet()) {
        _dataVersions.update { it.changed(change, ids) }
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
            initialized = true
        }
    }
}
