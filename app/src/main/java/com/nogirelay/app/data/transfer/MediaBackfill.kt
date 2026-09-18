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
    /** 返回 404 的媒体。下载器会保留永久的未找到标记，因此不会重试。 */
    val notFound: Int,
    val failed: Int,
    val bytes: Long,
    val errors: List<String>,
)

/**
 * 下载调用方已经识别为缺失的媒体。
 *
 * 该列表直接来自导出预览——它遍历了相同的记录以生成「补齐缺失媒体（N）」计数，
 * 因此这一轮永远不会重新读取数据库。
 * 每个项目都会让进度前进一次，这也使每个项目都成为一个取消点。
 *
 * 404 被视为最终结果：下载器保留其永久的「未找到」标记，该次尝试被报告为跳过，因此后续轮次
 * 不会再为它访问网络。
 */
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
            // 预览只是一个快照，因此文件可能在此期间已经到达。
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
