package com.nogirelay.app.blog

import android.util.LruCache

internal object BlogTextCache {
    data class Parsed(val html: String, val blocks: List<BlogContentBlock>, val plainText: String)
    private val cache = object : LruCache<String, Parsed>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Parsed): Int =
            (value.html.length + value.plainText.length + value.blocks.sumOf {
                when (it) {
                    is BlogContentBlock.Text -> it.value.length
                    is BlogContentBlock.Image -> it.url.length
                }
            }).coerceAtMost(Int.MAX_VALUE / 2) * 2
    }

    fun parse(id: String, html: String): Parsed {
        cache.get(id)?.takeIf { it.html == html }?.let { return it }
        val blocks = BlogContentParser.blocks(html)
        return Parsed(html, blocks, BlogContentParser.plainText(blocks)).also { cache.put(id, it) }
    }
}
