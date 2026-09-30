package com.nogirelay.app.performance

internal class ImageMemoryStore<T>(private val maxBytes: Long, private val sizeOf: (T) -> Long) {
    private data class Entry<T>(val url: String, val value: T, val bytes: Long)
    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private val latestByUrl = mutableMapOf<String, String>()
    var bytes: Long = 0
        private set

    @Synchronized fun get(key: String): T? = entries[key]?.value
    @Synchronized fun getForUrl(url: String): T? = latestByUrl[url]?.let { entries[it]?.value }

    @Synchronized fun removeForUrl(url: String) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.url == url) {
                bytes -= entry.bytes
                iterator.remove()
            }
        }
        latestByUrl.remove(url)
    }

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

internal fun imageScaledDensities(
    width: Int,
    height: Int,
    sampleSize: Int,
    targetWidth: Int,
    targetHeight: Int,
): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || sampleSize <= 0) return null
    if (targetWidth <= 0 && targetHeight <= 0) return null
    val sampledWidth = width / sampleSize
    val sampledHeight = height / sampleSize
    if (sampledWidth <= 0 || sampledHeight <= 0) return null
    val scale = when {
        targetWidth <= 0 -> targetHeight.toFloat() / sampledHeight
        targetHeight <= 0 -> targetWidth.toFloat() / sampledWidth
        else -> maxOf(targetWidth.toFloat() / sampledWidth, targetHeight.toFloat() / sampledHeight)
    }
    if (scale >= 1f) return null
    val scaledWidth = (sampledWidth * scale).toInt().coerceAtLeast(1)
    if (scaledWidth >= sampledWidth) return null

    return sampledWidth to scaledWidth
}
