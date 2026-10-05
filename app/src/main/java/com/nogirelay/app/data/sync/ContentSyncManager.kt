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
    private const val TAG = "NogiRelay"

    fun syncContent(context: Context): SyncOutcome {
        val messageResult = runCatching { syncMessagesFromServer(context) }
        val blogResult = runCatching { syncBlogsFromOfficial(context) }
        val messageError = messageResult.exceptionOrNull()
        val blogError = blogResult.exceptionOrNull()
        if (messageError != null && blogError != null) {
            // The message server's reason (token, address) is the one to act on.
            throw messageError.apply { addSuppressed(blogError) }
        }
        messageResult.exceptionOrNull()?.let { Log.w(TAG, "Message sync failed", it) }
        blogResult.exceptionOrNull()?.let { Log.w(TAG, "BLOG sync failed", it) }
        return SyncOutcome(messageResult.getOrDefault(0), blogResult.getOrDefault(0))
    }

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
            AppGraph.blogMembers.replaceBlogMembers(AppGraph.blogClient.fetchMembers())
        }.onFailure { error ->
            Log.w(TAG, "BLOG member directory sync failed; keeping the last successful list", error)
        }
        val fullSyncComplete = AppGraph.syncState.isBlogFullSyncComplete()
        val syncBoundaryId = AppGraph.syncState.blogSyncHeadId()
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
                    if (AppGraph.blogs.upsertBlog(post, isUnread = isUnread)) {
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
                AppGraph.syncState.markBlogSyncHead(newestId)
                if (finalCount != expectedCount || finalHeadId != newestId) {
                    Log.d(TAG, "BLOG 增量同步期间官网发布了新博客 (head=$finalHeadId, syncedHead=$newestId)；已安全推进边界至 $newestId，新增博客将在下个周期同步")
                }
            }
            Log.d(TAG, "BLOG incremental sync complete: inserted=$inserted")
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

                    if (AppGraph.blogs.upsertBlog(post)) inserted += 1
                    BlogMediaDownloader.enqueue(context, post)
                }
                offset += page.posts.size
            }
            val finalCount = AppGraph.blogClient.fetchCount()
            val finalHead = AppGraph.blogClient.fetchPage(limit = pageSize, offset = 0)
            val headCovered = finalHead.posts.all { it.id in seenIds }
            if (stable && expectedCount == finalCount && seenIds.size == finalCount && headCovered) {
                AppGraph.syncState.markBlogFullSyncComplete(finalHead.posts.firstOrNull()?.id)
                Log.d(TAG, "BLOG full sync verified: count=$finalCount, attempts=${attempt + 1}")
                return inserted
            }
            Log.w(
                TAG,
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

        val fullSyncComplete = AppGraph.syncState.isMessageFullSyncComplete()
        val syncBoundaryId = AppGraph.syncState.messageSyncHeadId()

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

        val countCovered = expectedCount == null || finalCount == null || expectedCount == finalCount
        if (!completed) {
            error("消息增量同步未能完整到达同步边界或末尾；未移动同步边界，下次将安全重试")
        }
        if (newestId != null) {
            AppGraph.syncState.markMessageSyncHead(newestId)
            if (!countCovered || finalHeadId != newestId) {
                Log.d(TAG, "消息增量同步期间服务器接收到新消息 (head=$finalHeadId, syncedHead=$newestId)；已安全推进边界至 $newestId，新增消息将在下个周期同步")
            }
        }
        Log.d(TAG, "Message incremental sync complete: inserted=${inserted}")
        return inserted
    }

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

                seenIds.size == offset
            }
            if (stable && countCovered && headCovered) {
                AppGraph.syncState.markMessageFullSyncComplete(finalHead.firstOrNull()?.id)
                Log.d(TAG, "Message full sync verified: count=${seenIds.size}, attempts=${attempt + 1}")
                return inserted
            }
            Log.w(
                TAG,
                "Message full sync snapshot changed; retrying: expected=$expectedCount final=$finalCount unique=${seenIds.size} walked=$offset headCovered=$headCovered",
            )
        }
        error("消息列表在同步期间持续变化；已保存抓到的内容，但未标记全量完成，下次会重新校验")
    }

    private fun messageCountOrNull(settings: AppSettings): Int? =
        runCatching { AppGraph.relayClient.fetchMessageCount(settings) }
            .onFailure { Log.w(TAG, "Message count unavailable; verifying with the head only", it) }
            .getOrNull()

    private fun storeSyncedMessage(context: Context, message: RelayMessage, isUnread: Boolean = false): Boolean {
        val inserted = AppGraph.messages.insert(message, isUnread = isUnread)
        if (message.type != MessageType.TEXT) {
            runCatching { MediaDownloader.enqueueIfNeeded(context, message) }
                .onFailure { error -> Log.w(TAG, "Media download enqueue failed for ${message.id}", error) }
        }
        return inserted
    }
}
