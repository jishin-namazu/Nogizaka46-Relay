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

    private fun syncBlogsFromOfficial(context: Context): Int {
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
                    if (AppGraph.database.upsertBlog(post, isUnread = isUnread)) inserted += 1
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
        val inserted = if (fullSyncComplete && syncBoundaryId != null) {
            syncNewMessages(context, settings, syncBoundaryId)
        } else {
            syncMessageHistory(context, settings)
        }
        TranslationManager.enqueue(context)
        return inserted
    }

    /**
     * Message sync after the first successful backfill, built like [syncBlogsFromOfficial]: the server
     * lists newest first, so the walk starts at offset 0 and stops the moment the previous head shows
     * up. The boundary only moves once the total and the head are confirmed unchanged; if the list moved
     * under us the walk is discarded so the next sync repeats it and picks up whatever arrived.
     */
    private fun syncNewMessages(context: Context, settings: AppSettings, syncBoundaryId: String): Int {
        val pageSize = 200
        val expectedCount = messageCountOrNull(settings)
        var offset = 0
        var inserted = 0
        var newestId: String? = null
        var completed = false
        while (true) {
            val page = AppGraph.relayClient.fetchMessages(settings, limit = pageSize, offset = offset)
            if (page.isEmpty()) {
                // Reaching the end is only trustworthy when the count agrees that we are at the end;
                // the previous head can also be gone after a server-side prune, which recovers here.
                if (expectedCount == null || offset >= expectedCount) completed = true
                break
            }
            if (newestId == null) newestId = page.firstOrNull()?.id
            val boundaryReached = page.any { it.id == syncBoundaryId }
            page.forEach {
                val isUnread = !MessageReadTracker.isViewing(it.memberKey)
                if (storeSyncedMessage(context, it, isUnread = isUnread)) inserted++
            }
            offset += page.size
            if (boundaryReached || (expectedCount != null && offset >= expectedCount) || page.size < pageSize) {
                completed = true
                break
            }
        }
        val finalCount = messageCountOrNull(settings)
        val finalHeadId = AppGraph.relayClient.fetchMessages(settings, limit = 1, offset = 0).firstOrNull()?.id
        // A null total only drops the count half of the check; the head comparison always runs.
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
     * One-off message backfill, built like the BLOG full sync: page through everything and record the
     * full-sync marker only when the snapshot was identical from start to finish — the total must not
     * move, a stable list hands every row out exactly once, and the newest page must be covered by this
     * pass. Anything else is retried rather than leaving a hole the boundary would then hide forever.
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
                    // Reading nothing while the total still promises rows means the list moved under us.
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
                // Older relay without /v1/messages/stats/summary: fall back to "no row came back twice",
                // which is what a shifted list produces.
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
     * Message total from the relay, or null when the server predates /v1/messages/stats/summary or is
     * briefly unreachable. Callers treat null as "total unknown" and verify with the head alone.
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
