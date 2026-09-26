package com.nogirelay.app.blog

import android.content.Context
import android.util.Log
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.isRealBlogImageUrl
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.data.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object BlogMediaDownloader {
    private const val TAG = "NogiBlogMedia"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val slots = Semaphore(3)

    fun enqueue(context: Context, post: BlogPost) {
        prefetchImages(context, imageUrls(post))
    }

/**
     * 在后台把给定图片下载到私有缓存中，已存在的文件会跳过。用于 BLOG 页面的
     * 封面，这样滚动时直接从磁盘解码，而不必等待新的下载，从而让搜索结果和
     * 最新文章一样流畅。
     */
    fun prefetchImages(context: Context, urls: List<String>) {
        val appContext = context.applicationContext
        urls.forEach { url ->
            if (url.isBlank() || MediaDownloader.isNotFound(appContext, url) || !queued.add(url)) return@forEach
            scope.launch {
                try {
                    slots.withPermit { MediaDownloader.downloadUrl(appContext, url, MessageType.IMAGE) }
                } catch (error: Throwable) {
                    Log.w(TAG, "BLOG image prefetch failed", error)
                } finally {
                    queued.remove(url)
                }
            }
        }
    }

    fun imageUrls(post: BlogPost): List<String> = buildList {
        post.imageUrl?.takeIf(::isRealBlogImageUrl)?.let(::add)
        addAll(
            BlogContentParser.blocks(post.bodyHtml)
                .filterIsInstance<BlogContentBlock.Image>()
                .map(BlogContentBlock.Image::url)
                .filter(String::isNotBlank),
        )
    }.distinct()
}
