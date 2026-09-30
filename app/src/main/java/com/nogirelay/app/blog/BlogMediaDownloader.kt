package com.nogirelay.app.blog

import android.content.Context
import android.util.Log
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.isRealBlogImageUrl
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.ui.preloadRemoteImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * Warms images in the page's background effect. Navigation must never
     * await this work; visible image controls reveal results independently.
     */
    suspend fun preloadImages(context: Context, urls: List<String>, limit: Int = 2) {
        val appContext = context.applicationContext
        coroutineScope {
            urls.distinct().take(limit).mapNotNull { url ->
                if (url.isBlank() || MediaDownloader.isNotFound(appContext, url)) {
                    null
                } else {
                    async(Dispatchers.IO) {
                        try {
                            slots.withPermit {
                                preloadRemoteImage(context, url, targetWidth = 720)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Log.w(TAG, "BLOG image preload failed", error)
                        }
                    }
                }
            }.awaitAll()
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
