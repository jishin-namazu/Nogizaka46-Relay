package com.nogirelay.app.data

import android.content.Context
import com.nogirelay.app.data.db.BlogDao
import com.nogirelay.app.data.db.BlogMemberDao
import com.nogirelay.app.data.db.MediaRefDao
import com.nogirelay.app.data.db.MessageDao
import com.nogirelay.app.data.db.SyncStateDao
import com.nogirelay.app.data.repository.BlogRepository
import com.nogirelay.app.data.repository.MessageRepository
import com.nogirelay.app.data.sync.SyncController
import com.nogirelay.app.media.MediaCacheRevision
import com.nogirelay.app.performance.PerformanceDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide wiring. DAOs answer synchronous queries and publish their own
 * writes to [invalidation]; repositories turn those changes into flows that
 * screens subscribe to.
 */
object AppGraph {
    @Volatile
    private var initialized = false
    val isInitialized: Boolean get() = initialized

    /** Work that must outlive any screen (syncs, playback bookkeeping). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var invalidation: DataInvalidationTracker
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var database: MessageDatabase
        private set
    lateinit var syncState: SyncStateDao
        private set
    lateinit var mediaRefs: MediaRefDao
        private set
    lateinit var messages: MessageDao
        private set
    lateinit var blogMembers: BlogMemberDao
        private set
    lateinit var blogs: BlogDao
        private set
    lateinit var messageRepository: MessageRepository
        private set
    lateinit var blogRepository: BlogRepository
        private set
    lateinit var sync: SyncController
        private set
    lateinit var relayClient: RelayClient
        private set
    lateinit var blogClient: BlogClient
        private set
    lateinit var dispatchers: PerformanceDispatchers
        private set

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            val exportPrefs = appContext.getSharedPreferences(EXPORT_VERSION_PREFS, Context.MODE_PRIVATE)
            val exportMessages = exportPrefs.getLong(EXPORT_MESSAGES, 0L)
            val exportBlogContent = exportPrefs.getLong(EXPORT_BLOG_CONTENT, 0L)
            invalidation = DataInvalidationTracker(
                initialExportVersions = DataVersions(
                    revision = maxOf(exportMessages, exportBlogContent),
                    messages = exportMessages,
                    blogContent = exportBlogContent,
                ),
                persistExportVersions = { versions ->
                    exportPrefs.edit()
                        .putLong(EXPORT_MESSAGES, versions.messages)
                        .putLong(EXPORT_BLOG_CONTENT, versions.blogContent)
                        .apply()
                },
            )
            dispatchers = PerformanceDispatchers()
            settings = SettingsStore(appContext, invalidation)
            database = MessageDatabase(appContext, invalidation)
            syncState = SyncStateDao(database)
            mediaRefs = MediaRefDao(database, syncState)
            messages = MessageDao(database, mediaRefs, invalidation)
            blogMembers = BlogMemberDao(database, invalidation)
            blogs = BlogDao(database, blogMembers, mediaRefs, invalidation)
            messageRepository = MessageRepository(messages, invalidation, dispatchers)
            blogRepository = BlogRepository(blogs, blogMembers, invalidation, dispatchers)
            relayClient = RelayClient()
            blogClient = BlogClient()
            sync = SyncController(appContext, applicationScope, Dispatchers.IO)
            MediaCacheRevision.initialize(appContext)
            initialized = true
        }
    }

    private const val EXPORT_VERSION_PREFS = "export_estimate_versions"
    private const val EXPORT_MESSAGES = "messages"
    private const val EXPORT_BLOG_CONTENT = "blog_content"
}
