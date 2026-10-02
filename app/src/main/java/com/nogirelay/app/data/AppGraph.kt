package com.nogirelay.app.data

import android.content.Context
import android.content.SharedPreferences
import com.nogirelay.app.media.MediaCacheRevision
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
    private val _exportDataVersions = MutableStateFlow(DataVersions())
    val exportDataVersions: StateFlow<DataVersions> = _exportDataVersions
    private var exportVersionPrefs: SharedPreferences? = null

    fun notifyDataChanged(
        change: DataChange = DataChange.ALL,
        ids: Set<String> = emptySet(),
        invalidateExportEstimate: Boolean = true,
    ) {
        if (invalidateExportEstimate) {
            _exportDataVersions.update { current ->
                current.changed(change, ids).also(::persistExportVersions)
            }
        }
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
            MediaCacheRevision.initialize(appContext)
            exportVersionPrefs = appContext.getSharedPreferences(EXPORT_VERSION_PREFS, Context.MODE_PRIVATE)
            exportVersionPrefs?.let { prefs ->
                val messages = prefs.getLong(EXPORT_MESSAGES, 0L)
                val blogContent = prefs.getLong(EXPORT_BLOG_CONTENT, 0L)
                _exportDataVersions.value = DataVersions(
                    revision = maxOf(messages, blogContent),
                    messages = messages,
                    blogContent = blogContent,
                )
            }
            initialized = true
        }
    }

    private fun persistExportVersions(versions: DataVersions) {
        exportVersionPrefs?.edit()
            ?.putLong(EXPORT_MESSAGES, versions.messages)
            ?.putLong(EXPORT_BLOG_CONTENT, versions.blogContent)
            ?.apply()
    }

    private const val EXPORT_VERSION_PREFS = "export_estimate_versions"
    private const val EXPORT_MESSAGES = "messages"
    private const val EXPORT_BLOG_CONTENT = "blog_content"
}
