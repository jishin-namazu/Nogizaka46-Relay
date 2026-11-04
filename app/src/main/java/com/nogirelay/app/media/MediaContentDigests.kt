package com.nogirelay.app.media

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Content SHA-256 of cached media files, kept in a `<file>.sha256` sidecar so exports can name
 * archive entries without re-reading the file. A sidecar is only trusted while the file's length
 * and modification time still match what was recorded.
 */
internal object MediaContentDigests {
    private const val SUFFIX = ".sha256"
    private const val BUFFER = 64 * 1024
    private val SHA_PATTERN = Regex("[0-9a-f]{64}")

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    fun cached(file: File): String? {
        val sidecar = sidecar(file)
        if (!file.isFile || !sidecar.isFile) return null
        return runCatching {
            val lines = sidecar.readLines()
            if (lines.size < 3) return@runCatching null
            val sha = lines[0].trim().lowercase()
            val length = lines[1].trim().toLong()
            val modified = lines[2].trim().toLong()
            sha.takeIf {
                SHA_PATTERN.matches(it) && length == file.length() && modified == file.lastModified()
            }
        }.getOrNull()
    }

    fun store(file: File, sha: String) {
        runCatching {
            if (!file.isFile) return@runCatching
            sidecar(file).writeText(
                sha.lowercase() + "\n" + file.length() + "\n" + file.lastModified() + "\n",
            )
        }
    }

    /** Returns the recorded digest, or hashes the file once and records it. */
    fun getOrCompute(file: File): String {
        cached(file)?.let { return it }
        val length = file.length()
        val modified = file.lastModified()
        val digest = newDigest()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val sha = hex(digest)
        // Skip recording if the file was replaced while it was being hashed.
        if (file.length() == length && file.lastModified() == modified) store(file, sha)
        return sha
    }

    fun delete(file: File) {
        runCatching { sidecar(file).delete() }
    }

    private fun sidecar(file: File): File = File(file.absolutePath + SUFFIX)
}
