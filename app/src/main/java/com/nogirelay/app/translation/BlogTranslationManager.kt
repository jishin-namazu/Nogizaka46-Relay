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

    private const val BULK_LIMIT = Int.MAX_VALUE

    /** 自动入口（同步完成 / App 启动 / 设置变更）：历史积压是否顺带翻译由开关决定。 */
    fun enqueuePending(context: Context) = enqueueAfterSync(context)

    /**
     * 自动翻译入口：先翻 [newIds] 这批刚写入的博客；只有用户打开"博客全量翻译"
     * （默认关闭）时才会顺带扫历史积压。
     */
    fun enqueueAfterSync(context: Context, newIds: Collection<String> = emptyList()) {
        val appContext = context.applicationContext
        scope.launch {
            AppGraph.initialize(appContext)
            val settings = AppGraph.settings.read()
            if (!isConfigured(settings)) return@launch
            if (newIds.isNotEmpty()) {
                val fresh = runCatching { AppGraph.database.pendingBlogTranslationsByIds(newIds) }.getOrNull()
                fresh?.forEach { blogId -> schedule(appContext, blogId, force = false, retryFailures = true) }
            }
            if (settings.blogFullTranslation) enqueuePendingInternal(appContext, BULK_LIMIT)
        }
    }

    /** 强制把整批历史积压排进队列："重新翻译全部"用，无视全量开关。 */
    fun enqueueAllPending(context: Context, limit: Int = BULK_LIMIT) {
        val appContext = context.applicationContext
        scope.launch { enqueuePendingInternal(appContext, limit) }
    }

    /** @param limit 这一轮会取走多少个待处理的 BLOG；批量处理会要求全部。 */
    private fun enqueuePendingInternal(appContext: Context, limit: Int) {
        AppGraph.initialize(appContext)
        val settings = AppGraph.settings.read()
        if (!isConfigured(settings)) return
        val pendingIds = runCatching { AppGraph.database.pendingBlogTranslations(limit) }.getOrNull() ?: return
        pendingIds.forEach { blogId ->
            schedule(appContext, blogId, force = false, retryFailures = true)
        }
    }

    private fun isConfigured(settings: com.nogirelay.app.data.AppSettings): Boolean =
        settings.translationEnabled && settings.aiApiKey.isNotBlank() && settings.aiModel.isNotBlank()

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
                    ).mapCatching(layout::validateAndSerialize).getOrThrow()
                    AppGraph.database.saveBlogTranslation(blogId, translation)
                    AppGraph.notifyDataChanged()
                }
            }
        }
}
