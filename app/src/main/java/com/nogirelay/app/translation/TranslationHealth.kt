package com.nogirelay.app.translation

import android.os.SystemClock
import com.nogirelay.app.data.AppSettings
import com.nogirelay.app.data.HttpStatusException
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranslationIssueKind {
    /** The key is wrong or lacks access. Paused until the settings change. */
    AUTH,

    /** The account has no balance or quota left. Paused until the settings change. */
    QUOTA,

    /** The chosen model does not exist or is not available to this key. Paused until the settings change. */
    MODEL,

    /** Too many requests; paused for a minute. */
    RATE_LIMITED,

    /** Network, server or response problems; individual items back off and retry. */
    TRANSIENT,
}

data class TranslationIssue(
    val kind: TranslationIssueKind,
    /** One line for status rows. */
    val title: String,
    /** What to do about it. */
    val advice: String,
    /** The provider's own message, shortened. */
    val detail: String,
    val provider: AIProviderType,
    val model: String,
    val occurredAtMillis: Long = System.currentTimeMillis(),
) {
    /** True when translation stopped and waits for the user (settings change or retry). */
    val blocking: Boolean get() = kind != TranslationIssueKind.TRANSIENT
}

/**
 * Shared health of the AI translation pipeline.
 *
 * A failure that cannot fix itself (bad key, no quota, unknown model) pauses
 * every translation queue and is shown in settings, instead of every message
 * failing and retrying on its own. Changing the provider, key or model, or
 * pressing retry, lifts the pause.
 */
object TranslationHealth {
    private val _issue = MutableStateFlow<TranslationIssue?>(null)
    val issue: StateFlow<TranslationIssue?> = _issue.asStateFlow()

    @Volatile private var pausedFor: String? = null
    @Volatile private var pausedUntilElapsed = 0L
    @Volatile private var transientFailures = 0

    /** Whether work may call the provider with [settings] right now. */
    fun canTranslate(settings: AppSettings): Boolean {
        val paused = pausedFor ?: return true
        val fingerprint = fingerprint(settings)
        val expired = pausedUntilElapsed > 0L && SystemClock.elapsedRealtime() >= pausedUntilElapsed
        if (paused != fingerprint || expired) {
            // New credentials or model, or the rate-limit window passed.
            clear()
            return true
        }
        return false
    }

    fun recordSuccess() {
        transientFailures = 0
        if (_issue.value != null && pausedFor == null) _issue.value = null
    }

    fun recordFailure(error: Throwable, settings: AppSettings) {
        val issue = classify(error, settings)
        when (issue.kind) {
            TranslationIssueKind.TRANSIENT -> {
                // One odd item is not worth a warning; a run of failures is.
                transientFailures += 1
                if (transientFailures >= TRANSIENT_REPORT_THRESHOLD && pausedFor == null) _issue.value = issue
            }
            TranslationIssueKind.RATE_LIMITED -> {
                pausedFor = fingerprint(settings)
                pausedUntilElapsed = SystemClock.elapsedRealtime() + RATE_LIMIT_PAUSE_MILLIS
                _issue.value = issue
            }
            else -> {
                pausedFor = fingerprint(settings)
                pausedUntilElapsed = 0L
                _issue.value = issue
            }
        }
    }

    /** Lifts any pause; the caller re-queues pending work. */
    fun clear() {
        pausedFor = null
        pausedUntilElapsed = 0L
        transientFailures = 0
        _issue.value = null
    }

    private fun fingerprint(settings: AppSettings) =
        "${settings.aiProvider}|${settings.aiApiKey.hashCode()}|${settings.aiModel.trim()}"

    internal fun classify(error: Throwable, settings: AppSettings): TranslationIssue {
        val http = generateSequence(error) { it.cause }.filterIsInstance<HttpStatusException>().firstOrNull()
        val body = (http?.message ?: error.message).orEmpty()
        val lower = body.lowercase()
        val quotaWords = listOf("quota", "insufficient", "balance", "billing", "credit", "余额", "欠费")
        val modelWords = listOf("model", "模型")
        val (kind, title, advice) = when {
            http != null && (http.status == 401 || http.status == 403) ->
                Triple(TranslationIssueKind.AUTH, "API Key 无效或没有权限", "请检查 API Key 是否正确、是否已开通该模型。")
            http != null && http.status == 402 || http?.status == 429 && quotaWords.any(lower::contains) ->
                Triple(TranslationIssueKind.QUOTA, "账户余额或额度不足", "请在供应商后台充值或更换 API Key。")
            http?.status == 429 ->
                Triple(TranslationIssueKind.RATE_LIMITED, "请求过于频繁，已暂停 1 分钟", "稍后会自动继续；也可以换用限额更高的模型。")
            http != null && (http.status == 404 || http.status == 400 && modelWords.any(lower::contains)) ->
                Triple(TranslationIssueKind.MODEL, "翻译模型不可用", "请在下方重新获取模型列表并选择可用模型。")
            http != null && http.status >= 500 ->
                Triple(TranslationIssueKind.TRANSIENT, "翻译服务暂时不可用", "供应商服务器出错，稍后会自动重试。")
            http != null ->
                Triple(TranslationIssueKind.TRANSIENT, "翻译请求被拒绝（HTTP ${http.status}）", "请查看下方的供应商说明，必要时更换模型。")
            generateSequence(error) { it.cause }.any { it is IOException } ->
                Triple(TranslationIssueKind.TRANSIENT, "无法连接翻译服务", "请检查网络，恢复后会自动重试。")
            else ->
                Triple(TranslationIssueKind.TRANSIENT, "翻译结果无法解析", "可以换用支持结构化输出的模型，失败的内容稍后会自动重试。")
        }
        return TranslationIssue(
            kind = kind,
            title = title,
            advice = advice,
            detail = body.replace(Regex("\\s+"), " ").take(DETAIL_LENGTH),
            provider = settings.aiProvider,
            model = settings.aiModel.trim(),
        )
    }

    private const val TRANSIENT_REPORT_THRESHOLD = 3
    private const val RATE_LIMIT_PAUSE_MILLIS = 60_000L
    private const val DETAIL_LENGTH = 160
}
