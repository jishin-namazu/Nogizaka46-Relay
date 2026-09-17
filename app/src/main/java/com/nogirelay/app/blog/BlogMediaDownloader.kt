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
     * Downloads the given images into the private cache in the background, skipping files that are
     * already there. Used for a BLOG page's covers so scrolling decodes from disk instead of waiting
     * for a fresh download, which keeps search results as smooth as the newest posts.
     */
    fun prefetchImages(context: Context, urls: List<String>) {
        val appContext = context.applicationContext
        urls.forEach { url ->
            if (url.isBlank() || !queued.add(url)) return@forEach
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
