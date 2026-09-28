package com.nogirelay.app.performance

/** URL fallbacks are indexes into the same byte-budgeted store, never a second owner. */
internal class ImageMemoryStore<T>(private val maxBytes: Long, private val sizeOf: (T) -> Long) {
    private data class Entry<T>(val url: String, val value: T, val bytes: Long)
    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private val latestByUrl = mutableMapOf<String, String>()
    var bytes: Long = 0
        private set

    @Synchronized fun get(key: String): T? = entries[key]?.value
    @Synchronized fun getForUrl(url: String): T? = latestByUrl[url]?.let { entries[it]?.value }

    @Synchronized fun put(key: String, url: String, value: T) {
        val size = sizeOf(value).coerceAtLeast(1)
        if (size > maxBytes) return
        entries.remove(key)?.let { old ->
            bytes -= old.bytes
            if (latestByUrl[old.url] == key) latestByUrl.remove(old.url)
        }
        entries[key] = Entry(url, value, size)
        latestByUrl[url] = key
        bytes += size
        trimTo(maxBytes)
    }

    @Synchronized fun trimTo(limit: Long) {
        val iterator = entries.entries.iterator()
        while (bytes > limit.coerceAtLeast(0) && iterator.hasNext()) {
            val (key, old) = iterator.next()
            bytes -= old.bytes
            if (latestByUrl[old.url] == key) latestByUrl.remove(old.url)
            iterator.remove()
        }
    }
}

/** Retain enough pixels for the displayed bounds; never upscale a smaller source. */
internal fun imageSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2 &&
        (targetWidth <= 0 || width / (sample * 2) >= targetWidth) &&
        (targetHeight <= 0 || height / (sample * 2) >= targetHeight) &&
        (targetWidth > 0 || targetHeight > 0)
    ) sample *= 2
    return sample
}
