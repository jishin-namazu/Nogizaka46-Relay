package com.nogirelay.app.translation

import android.content.Context
import android.util.Log
import com.nogirelay.app.blog.BlogContentParser
import com.nogirelay.app.data.AppGraph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object BlogTranslationManager {
    private const val TAG = "NogiBlogTranslation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val deferred = ConcurrentHashMap.newKeySet<String>()
    private val requestSlots = Semaphore(3)

    private data class Work(val context: Context, val id: String, val force: Boolean, val retryFailures: Boolean)
    private var bulkJob: kotlinx.coroutines.Job? = null
    private val queue = com.nogirelay.app.performance.BoundedWorkQueue<Work>(
        scope, parallelism = 3, capacity = 24, key = { it.id },
        onFailure = { work, error ->
            if (work.retryFailures) {
                val attempts = (retryCount.merge(work.id, 1, Int::plus) ?: 1).coerceAtMost(8)
                retryAfter[work.id] = System.currentTimeMillis() + (5_000L * (1L shl (attempts - 1))).coerceAtMost(300_000L)
            }
            Log.w(TAG, "BLOG translation failed for ${work.id}", error)
        },
    ) { work ->
        if (isConfigured(AppGraph.settings.read())) {
            translate(work.context, work.id, work.force).getOrThrow()
            retryAfter.remove(work.id)
            retryCount.remove(work.id)
        }
    }

    fun enqueue(context: Context, blogId: String, force: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch { schedule(appContext, blogId, force, retryFailures = false) }
    }

    fun enqueuePending(context: Context) = enqueueAfterSync(context)

    fun enqueueAfterSync(context: Context, newIds: Collection<String> = emptyList()) {
        val appContext = context.applicationContext
        AppGraph.initialize(appContext)
        if (newIds.isNotEmpty()) scope.launch {
            if (isConfigured(AppGraph.settings.read())) newIds.forEach { schedule(appContext, it, false, true) }
        }
        if (AppGraph.settings.read().blogFullTranslation) enqueueBacklog(appContext, manual = false)
    }

    fun enqueueAllPending(context: Context, limit: Int = Int.MAX_VALUE) =
        enqueueBacklog(context.applicationContext, manual = true, limit = limit)

    @Synchronized
    private fun enqueueBacklog(context: Context, manual: Boolean, limit: Int = Int.MAX_VALUE) {
        if (bulkJob?.isActive == true) {
            if (!manual) return
            bulkJob?.cancel()
        }
        bulkJob = scope.launch {
            AppGraph.initialize(context)
            var afterId: String? = null
            var remaining = limit.coerceAtLeast(0)
            while (remaining > 0) {
                val settings = AppGraph.settings.read()
                if (!isConfigured(settings) || !manual && !settings.blogFullTranslation) break
                val ids = AppGraph.blogs.pendingTranslationIds(afterId, minOf(24, remaining))
                if (ids.isEmpty()) break
                ids.forEach { schedule(context, it, false, true) }
                afterId = ids.last()
                remaining -= ids.size
            }
        }
    }

    private fun isConfigured(settings: com.nogirelay.app.data.AppSettings): Boolean =
        settings.translationEnabled && settings.aiApiKey.isNotBlank() && settings.aiModel.isNotBlank()

    suspend fun translate(context: Context, blogId: String, force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        AppGraph.initialize(context)
        if (!inFlight.add(blogId)) return@withContext Result.success(Unit)
        try {

            requestSlots.withPermit { translateInternal(context.applicationContext, blogId, force) }
        } finally {
            inFlight.remove(blogId)
        }
    }

    /** Re-queues what a translation pause skipped (after a settings change or a retry). */
    fun resumeAfterPause(context: Context) {
        resetRetries()
        val ids = deferred.toList()
        deferred.removeAll(ids.toSet())
        ids.forEach { enqueue(context, it) }
        enqueuePending(context)
    }

    fun resetRetries() {
        retryAfter.clear()
        retryCount.clear()
    }

    private suspend fun schedule(context: Context, blogId: String, force: Boolean, retryFailures: Boolean) {
        if (retryFailures && (retryAfter[blogId] ?: 0L) > System.currentTimeMillis()) return
        queue.enqueue(Work(context, blogId, force, retryFailures))
    }

    private suspend fun translateInternal(context: Context, blogId: String, force: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                AppGraph.initialize(context)
                val settings = AppGraph.settings.read()
                require(settings.translationEnabled) { "请先启用翻译" }
                require(settings.aiApiKey.isNotBlank() && settings.aiModel.isNotBlank()) { "请先配置 API Key 和翻译模型" }
                if (force) AppGraph.blogs.markBlogForRetranslation(blogId)
                val blog = AppGraph.blogs.findBlog(blogId) ?: error("BLOG 不存在")
                if (blog.bodyHtml.isBlank()) {
                    Log.d(TAG, "BLOG $blogId bodyHtml is blank; skipping translation until content is fetched")
                    return@runCatching
                }
                if (blog.translationDone && !force) return@runCatching
                if (!TranslationHealth.canTranslate(settings)) {
                    deferred += blogId
                    return@runCatching
                }
                val blocks = BlogContentParser.blocks(blog.bodyHtml)
                val bodyText = BlogContentParser.plainText(blocks)
                val source = listOf(blog.title.trim(), bodyText).filter(String::isNotBlank).joinToString("\n\n\n")
                if (source.isBlank()) {
                    AppGraph.blogs.saveBlogTranslation(blogId, null)
                    return@runCatching
                }
                val layout = BlogTranslationLayout.from(source)
                val provider = AIProviderFactory.getProvider(settings.aiProvider)
                val translation = provider.translate(
                    settings.aiApiKey,
                    settings.aiModel.trim(),
                    layout.requestPayload,
                ).mapCatching(layout::validateAndSerialize)
                    .onFailure { TranslationHealth.recordFailure(it, settings) }
                    .getOrThrow()
                AppGraph.blogs.saveBlogTranslation(blogId, translation)
                TranslationHealth.recordSuccess()
            }
        }
}
