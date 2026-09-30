package com.nogirelay.app.blog

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.isRealBlogImageUrl
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.ui.ImageAspectRatioCache
import kotlinx.coroutines.CoroutineScope
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
     * Warms a small set of images and waits for the downloads to finish.
     * Pagination uses this for the first two cards so the new page does not
     * reveal itself while its leading previews are still cold.
     */
    suspend fun preloadImages(context: Context, urls: List<String>) {
        val appContext = context.applicationContext
        coroutineScope {
            urls.distinct().take(2).mapNotNull { url ->
                if (url.isBlank() || MediaDownloader.isNotFound(appContext, url)) {
                    null
                } else {
                    async(Dispatchers.IO) {
                        try {
                            slots.withPermit {
                                val file = MediaDownloader.downloadUrl(appContext, url, MessageType.IMAGE)
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(file.absolutePath, bounds)
                                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                                    ImageAspectRatioCache.put(url, bounds.outWidth.toFloat() / bounds.outHeight.toFloat())
                                }
                            }
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
