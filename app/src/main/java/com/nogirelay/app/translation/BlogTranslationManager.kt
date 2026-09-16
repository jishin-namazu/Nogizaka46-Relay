package com.nogirelay.app.translation

import android.content.Context
import android.util.Log
import com.nogirelay.app.blog.BlogContentParser
import com.nogirelay.app.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object BlogTranslationManager {
    private const val TAG = "NogiBlogTranslation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val requestSlots = Semaphore(3)

    fun enqueue(context: Context, blogId: String, force: Boolean = false) {
        schedule(context.applicationContext, blogId, force, retryFailures = false)
    }

    fun enqueuePending(context: Context) {
        val appContext = context.applicationContext
        scope.launch { enqueuePendingInternal(appContext) }
    }

    private fun enqueuePendingInternal(appContext: Context) {
        AppGraph.initialize(appContext)
        val settings = AppGraph.settings.read()
        if (!settings.translationEnabled || settings.aiApiKey.isBlank() || settings.aiModel.isBlank()) return
        val pendingIds = runCatching { AppGraph.database.pendingBlogTranslations() }.getOrNull() ?: return
        pendingIds.forEach { blogId ->
            schedule(appContext, blogId, force = false, retryFailures = true)
        }
    }

    suspend fun translate(context: Context, blogId: String, force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        AppGraph.initialize(context)
        if (!inFlight.add(blogId)) return@withContext Result.success(Unit)
        try {
            translateInternal(context.applicationContext, blogId, force)
        } finally {
            inFlight.remove(blogId)
        }
    }

    fun resetRetries() {
        retryAfter.clear()
        retryCount.clear()
    }

    private fun schedule(context: Context, blogId: String, force: Boolean, retryFailures: Boolean) {
        val now = System.currentTimeMillis()
        if (retryFailures && (retryAfter[blogId] ?: 0L) > now) return
        if (!inFlight.add(blogId)) return
        scope.launch {
            try {
                translateInternal(context, blogId, force).getOrThrow()
                retryAfter.remove(blogId)
                retryCount.remove(blogId)
            } catch (error: Exception) {
                if (retryFailures) {
                    val attempts = (retryCount.merge(blogId, 1, Int::plus) ?: 1).coerceAtMost(8)
                    val delayMs = (5_000L * (1L shl (attempts - 1))).coerceAtMost(5 * 60_000L)
                    retryAfter[blogId] = System.currentTimeMillis() + delayMs
                    Log.w(TAG, "BLOG translation failed for $blogId; retrying in ${delayMs / 1000}s: ${error.message}", error)
                } else {
                    Log.w(TAG, "BLOG translation failed for $blogId: ${error.message}", error)
                }
            } finally {
                inFlight.remove(blogId)
            }
        }
    }

    private suspend fun translateInternal(context: Context, blogId: String, force: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                AppGraph.initialize(context)
                val settings = AppGraph.settings.read()
                require(settings.translationEnabled) { "请先启用翻译" }
                require(settings.aiApiKey.isNotBlank() && settings.aiModel.isNotBlank()) { "请先配置 API Key 和翻译模型" }
                if (force) AppGraph.database.markBlogForRetranslation(blogId)
                val blog = AppGraph.database.findBlog(blogId) ?: error("BLOG 不存在")
                if (blog.bodyHtml.isBlank()) {
                    Log.d(TAG, "BLOG $blogId bodyHtml is blank; skipping translation until content is fetched")
                    return@runCatching
                }
                if (blog.translationDone && !force) return@runCatching
                val blocks = BlogContentParser.blocks(blog.bodyHtml)
                val bodyText = BlogContentParser.plainText(blocks)
                val source = listOf(blog.title.trim(), bodyText).filter(String::isNotBlank).joinToString("\n\n\n")
                if (source.isBlank()) {
                    AppGraph.database.saveBlogTranslation(blogId, null)
                    return@runCatching
                }
                val layout = BlogTranslationLayout.from(source)
                val provider = AIProviderFactory.getProvider(settings.aiProvider)
                requestSlots.withPermit {
                    val translation = provider.translate(
                        settings.aiApiKey,
                        settings.aiModel.trim(),
                        layout.requestPayload,
                        settings.userNickname,
                    ).mapCatching(layout::validateAndSerialize).getOrThrow()
                    AppGraph.database.saveBlogTranslation(blogId, translation)
                }
            }
        }
}
