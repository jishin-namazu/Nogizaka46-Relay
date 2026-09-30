package com.nogirelay.app.data.transfer

import android.content.Context
import com.nogirelay.app.media.HttpNotFoundException
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class MediaBackfillReport(
    val downloaded: Int,
    val reused: Int,

    val notFound: Int,
    val failed: Int,
    val bytes: Long,
    val errors: List<String>,
)

object MediaBackfill {
    private const val PHASE = "补齐媒体"
    private const val MAX_REPORTED_ERRORS = 10

    suspend fun download(
        context: Context,
        candidates: List<MediaCandidate>,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
    ): MediaBackfillReport {
        var downloaded = 0
        var reused = 0
        var notFound = 0
        var failed = 0
        var bytes = 0L
        val errors = mutableListOf<String>()

        onProgress(PHASE, 0, candidates.size)
        candidates.forEachIndexed { index, candidate ->
            coroutineContext.ensureActive()

            if (MediaDownloader.cachedFileForUrl(context, candidate.url, candidate.type) != null) {
                reused += 1
            } else {
                try {
                    val file = MediaDownloader.downloadUrl(context, candidate.url, candidate.type)
                    downloaded += 1
                    bytes += file.length()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (missing: HttpNotFoundException) {
                    notFound += 1
                } catch (error: Throwable) {
                    failed += 1
                    if (errors.size < MAX_REPORTED_ERRORS) {
                        errors += (error.message ?: "下载失败") + "：" + candidate.url
                    }
                }
            }
            onProgress(PHASE, index + 1, candidates.size)
        }

        return MediaBackfillReport(
            downloaded = downloaded,
            reused = reused,
            notFound = notFound,
            failed = failed,
            bytes = bytes,
            errors = errors,
        )
    }
}
