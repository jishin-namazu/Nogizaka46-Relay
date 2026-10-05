package com.nogirelay.app.media

import android.util.LruCache
import java.security.MessageDigest

/**
 * SHA-256 names of media URLs. Every image bind and cache lookup needs the
 * name of its file, so recent ones are kept instead of hashing again.
 */
internal object UrlDigests {
    private val cache = LruCache<String, String>(2048)
    private val hexDigits = "0123456789abcdef".toCharArray()

    fun sha256(url: String): String {
        cache.get(url)?.let { return it }
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return hex(bytes).also { cache.put(url, it) }
    }

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = hexDigits[value ushr 4]
            chars[index * 2 + 1] = hexDigits[value and 0x0f]
        }
        return String(chars)
    }
}
