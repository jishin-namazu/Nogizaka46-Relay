package com.nogirelay.app.data.sync

import android.content.Context
import android.util.Log
import com.nogirelay.app.blog.BlogMediaDownloader
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.AppSettings
import com.nogirelay.app.data.BlogReadTracker
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.api.ApiConfig
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager

data class SyncOutcome(val messages: Int, val blogs: Int)

object ContentSyncManager {

    fun syncContent(context: Context): SyncOutcome {
        val messageResult = runCatching { syncMessagesFromServer(context) }
        val blogResult = runCatching { syncBlogsFromOfficial(context) }
        if (messageResult.isFailure && blogResult.isFailure) {
            throw IllegalStateException(
                "消息同步失败：${messageResult.exceptionOrNull()?.message}；BLOG 同步失败：${blogResult.exceptionOrNull()?.message}",
            )
        }
        messageResult.exceptionOrNull()?.let { Log.w("NogiRelay", "Message sync failed", it) }
        blogResult.exceptionOrNull()?.let { Log.w("NogiRelay", "BLOG sync failed", it) }
        return SyncOutcome(messageResult.getOrDefault(0), blogResult.getOrDefault(0))
    }

    /**
     * 包装 BLOG 遍历，使这一轮写入的 id 能送达翻译器：新的 BLOG
     * 总是会被翻译，而历史积压只有在"博客全量翻译"打开时才会推进。
     */
    private fun syncBlogsFromOfficial(context: Context): Int {
        val newBlogIds = linkedSetOf<String>()
        return try {
            syncBlogPages(context, newBlogIds)
        } finally {
            if (newBlogIds.isNotEmpty()) BlogTranslationManager.enqueueAfterSync(context, newBlogIds)
        }
    }

    private fun syncBlogPages(context: Context, newBlogIds: MutableSet<String>): Int {
        val pageSize = 100
        runCatching {
            AppGraph.database.replaceBlogMembers(AppGraph.blogClient.fetchMembers())
        }.onFailure { error ->
            Log.w("NogiRelay", "BLOG member directory sync failed; keeping the last successful list", error)
        }
        val fullSyncComplete = AppGraph.database.isBlogFullSyncComplete()
        val syncBoundaryId = AppGraph.database.blogSyncHeadId()
        var inserted = 0

        if (fullSyncComplete && syncBoundaryId != null) {
            var offset = 0
            var newestId: String? = null
            var expectedCount: Int? = null
            var completed = false
            while (true) {
                val page = AppGraph.blogClient.fetchPage(limit = pageSize, offset = offset)
                if (expectedCount == null) expectedCount = page.total
                if (page.posts.isEmpty()) {
                    if (offset >= page.total) completed = true else error("BLOG 增量分页在尾页前返回空数据")
                    break
                }
                if (newestId == null) newestId = page.posts.firstOrNull()?.id
                val boundaryReached = page.posts.any { it.id == syncBoundaryId }
                page.posts.forEach { post ->
                    val isUnread = !BlogReadTracker.isViewing(post.id)
                    if (AppGraph.database.upsertBlog(post, isUnread = isUnread)) {
                        inserted += 1
                        newBlogIds += post.id
                    }
                    BlogMediaDownloader.enqueue(context, post)
                }
                offset += page.posts.size
                if (boundaryReached || offset >= page.total || page.posts.size < pageSize) {
                    completed = true
                    break
                }
            }
            val finalCount = AppGraph.blogClient.fetchCount()
            val finalHeadId = AppGraph.blogClient.fetchPage(limit = 1, offset = 0).posts.firstOrNull()?.id
            if (!completed) {
                error("BLOG 增量同步未能完整到达同步边界或末尾；未移动同步边界，下次将安全重试")
            }
            if (newestId != null) {
                AppGraph.database.markBlogSyncHead(newestId)
                if (finalCount != expectedCount || finalHeadId != newestId) {
                    Log.d("NogiRelay", "BLOG 增量同步期间官网发布了新博客 (head=$finalHeadId, syncedHead=$newestId)；已安全推进边界至 $newestId，新增博客将在下个周期同步")
                }
            }
            Log.d("NogiRelay", "BLOG incremental sync complete: inserted=$inserted")
            return inserted
        }

        repeat(3) { attempt ->
            val expectedCount = AppGraph.blogClient.fetchCount()
            val seenIds = mutableSetOf<String>()
            var offset = 0
            var stable = true
            while (offset < expectedCount) {
                val page = AppGraph.blogClient.fetchPage(limit = pageSize, offset = offset)
                if (page.total != expectedCount || page.posts.isEmpty()) {
                    stable = false
                    break
                }
                page.posts.forEach { post ->
                    seenIds += post.id
                    // 全量回填属于历史内容，不当作"新博客"，只有增量分支才会喂给翻译器。
                    if (AppGraph.database.upsertBlog(post)) inserted += 1
                    BlogMediaDownloader.enqueue(context, post)
                }
                offset += page.posts.size
            }
            val finalCount = AppGraph.blogClient.fetchCount()
            val finalHead = AppGraph.blogClient.fetchPage(limit = pageSize, offset = 0)
            val headCovered = finalHead.posts.all { it.id in seenIds }
            if (stable && expectedCount == finalCount && seenIds.size == finalCount && headCovered) {
                AppGraph.database.markBlogFullSyncComplete(finalHead.posts.firstOrNull()?.id)
                Log.d("NogiRelay", "BLOG full sync verified: count=$finalCount, attempts=${attempt + 1}")
                return inserted
            }
            Log.w(
                "NogiRelay",
                "BLOG full sync snapshot changed; retrying: expected=$expectedCount, final=$finalCount, unique=${seenIds.size}, headCovered=$headCovered",
            )
        }
        error("BLOG 列表在同步期间持续变化；已保存抓到的内容，但未标记全量完成，下次会重新校验")
    }

    private fun syncMessagesFromServer(context: Context): Int {
        val savedSettings = AppGraph.settings.read()
        val settings = savedSettings.copy(
            relayUrl = savedSettings.relayUrl.ifBlank { ApiConfig.BASE_URL },
            accessToken = savedSettings.accessToken.ifBlank { ApiConfig.ACCESS_TOKEN },
        )
        if (settings.relayUrl.isBlank() || settings.accessToken.isBlank()) return 0

        val fullSyncComplete = AppGraph.database.isMessageFullSyncComplete()
        val syncBoundaryId = AppGraph.database.messageSyncHeadId()
        // 只有增量同步抓到的那批算"新消息"；首次全量回填属于历史积压，
        // 交给"消息全量翻译"开关（打开时 [TranslationManager.enqueueAfterSync] 会整批扫）。
        val newMessageIds = linkedSetOf<String>()
        return try {
            if (fullSyncComplete && syncBoundaryId != null) {
                syncNewMessages(context, settings, syncBoundaryId, newMessageIds)
            } else {
                syncMessageHistory(context, settings)
            }
        } finally {
            TranslationManager.enqueueAfterSync(context, newMessageIds)
        }
    }

    /**
     * 首次成功回填之后的消息同步，构建方式类似 [syncBlogsFromOfficial]：服务器
     * 按最新优先列出，因此遍历从 offset 0 开始，并在上一批头部出现的那一刻停止。
     * 只有在总数和头部都被确认未变时边界才会移动；如果列表在遍历过程中
     * 发生了变化，遍历结果会被丢弃，以便下次同步重复执行并抓取新到的内容。
     */
    private fun syncNewMessages(
        context: Context,
        settings: AppSettings,
        syncBoundaryId: String,
        newMessageIds: MutableSet<String>,
    ): Int {
        val pageSize = 200
        val expectedCount = messageCountOrNull(settings)
        var offset = 0
        var inserted = 0
        var newestId: String? = null
        var completed = false
        while (true) {
            val page = AppGraph.relayClient.fetchMessages(settings, limit = pageSize, offset = offset)
            if (page.isEmpty()) {
                // 只有在计数与"已到末尾"一致时，到达末尾才可信；
                // 上一批头部也可能在服务器端清理后消失，这里会恢复。
                if (expectedCount == null || offset >= expectedCount) completed = true
                break
            }
            if (newestId == null) newestId = page.firstOrNull()?.id
            val boundaryReached = page.any { it.id == syncBoundaryId }
            page.forEach {
                val isUnread = !MessageReadTracker.isViewing(it.memberKey)
                if (storeSyncedMessage(context, it, isUnread = isUnread)) {
                    inserted++
                    newMessageIds += it.id
                }
            }
            offset += page.size
            if (boundaryReached || (expectedCount != null && offset >= expectedCount) || page.size < pageSize) {
                completed = true
                break
            }
        }
        val finalCount = messageCountOrNull(settings)
        val finalHeadId = AppGraph.relayClient.fetchMessages(settings, limit = 1, offset = 0).firstOrNull()?.id
        // total 为 null 只会去掉校验中的计数那一半；头部比较始终执行。
        val countCovered = expectedCount == null || finalCount == null || expectedCount == finalCount
        if (!completed) {
            error("消息增量同步未能完整到达同步边界或末尾；未移动同步边界，下次将安全重试")
        }
        if (newestId != null) {
            AppGraph.database.markMessageSyncHead(newestId)
            if (!countCovered || finalHeadId != newestId) {
                Log.d("NogiRelay", "消息增量同步期间服务器接收到新消息 (head=$finalHeadId, syncedHead=$newestId)；已安全推进边界至 $newestId，新增消息将在下个周期同步")
            }
        }
        Log.d("NogiRelay", "Message incremental sync complete: inserted=${inserted}")
        return inserted
    }

    /**
     * 一次性消息回填，构建方式类似 BLOG 全量同步：逐页遍历全部内容，并且
     * 仅当快照从开始到结束完全一致时才记录全量同步标记——总数不能
     * 变动，稳定的列表会让每一行恰好出现一次，且最新一页必须被这一轮
     * 遍历覆盖。任何其他情况都会重试，而不是留下一个随后会被边界永久掩盖的缺口。
     */
    private fun syncMessageHistory(context: Context, settings: AppSettings): Int {
        val pageSize = 200
        var inserted = 0
        repeat(3) { attempt ->
            val expectedCount = messageCountOrNull(settings)
            val seenIds = mutableSetOf<String>()
            var offset = 0
            var stable = true
            while (expectedCount == null || offset < expectedCount) {
                val page = AppGraph.relayClient.fetchMessages(settings, limit = pageSize, offset = offset)
                if (page.isEmpty()) {
                    // 在 total 仍承诺有行的情况下什么都没读到，意味着列表在遍历过程中发生了变化。
                    if (expectedCount != null && offset < expectedCount) stable = false
                    break
                }
                page.forEach {
                    seenIds += it.id
                    if (storeSyncedMessage(context, it)) inserted++
                }
                offset += page.size
                if (page.size < pageSize) break
            }
            val finalCount = messageCountOrNull(settings)
            val finalHead = AppGraph.relayClient.fetchMessages(settings, limit = pageSize, offset = 0)
            val headCovered = finalHead.all { it.id in seenIds }
            val countCovered = if (expectedCount != null && finalCount != null) {
                expectedCount == finalCount && seenIds.size == finalCount
            } else {
                // 较旧的 relay 没有 /v1/messages/stats/summary：退回到"没有任何行重复出现"的
                // 判定，这正是列表发生偏移时产生的结果。
                seenIds.size == offset
            }
            if (stable && countCovered && headCovered) {
                AppGraph.database.markMessageFullSyncComplete(finalHead.firstOrNull()?.id)
                Log.d("NogiRelay", "Message full sync verified: count=${seenIds.size}, attempts=${attempt + 1}")
                return inserted
            }
            Log.w(
                "NogiRelay",
                "Message full sync snapshot changed; retrying: expected=$expectedCount final=$finalCount unique=${seenIds.size} walked=$offset headCovered=$headCovered",
            )
        }
        error("消息列表在同步期间持续变化；已保存抓到的内容，但未标记全量完成，下次会重新校验")
    }

    /**
     * 来自 relay 的消息总数；当服务器早于 /v1/messages/stats/summary 或
     * 短暂不可达时为 null。调用方将 null 视为"总数未知"，并仅用头部进行校验。
     */
    private fun messageCountOrNull(settings: AppSettings): Int? =
        runCatching { AppGraph.relayClient.fetchMessageCount(settings) }
            .onFailure { Log.w("NogiRelay", "Message count unavailable; verifying with the head only", it) }
            .getOrNull()

    private fun storeSyncedMessage(context: Context, message: RelayMessage, isUnread: Boolean = false): Boolean {
        val inserted = AppGraph.database.insert(message, isUnread = isUnread)
        if (message.type != MessageType.TEXT) {
            runCatching { MediaDownloader.enqueueIfNeeded(context, message) }
                .onFailure { error -> Log.w("NogiRelay", "Media download enqueue failed for ${message.id}", error) }
        }
        return inserted
    }
}
