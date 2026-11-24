package com.nogirelay.app.translation

import com.nogirelay.app.data.HttpStatusException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object TieredTranslation {
    /**
     * Runs a single translation item across [levels] (defaulting to the model's
     * deduplicated `none -> low -> high` sequence from [AIProvider.thinkingLevelsFor]).
     *
     * Escalates to the next thinking level when the model returns unparseable or
     * structurally invalid output, or when the provider rejects a lower tier's
     * thinking/reasoning parameter with HTTP 400. Auth, quota, rate-limit, model,
     * server, and network failures stop immediately so outer health/backoff logic
     * handles them without burning higher-thinking requests.
     */
    suspend fun <T> execute(
        provider: AIProvider,
        apiKey: String,
        model: String,
        requestPayload: String,
        levels: List<ThinkingLevel> = provider.thinkingLevelsFor(model),
        onEscalate: (from: ThinkingLevel, to: ThinkingLevel, error: Throwable) -> Unit = { _, _, _ -> },
        parseOutput: (String) -> T,
    ): T {
        val sequence = levels.ifEmpty { listOf(ThinkingLevel.NONE) }
        var lastError: Throwable? = null
        for ((index, level) in sequence.withIndex()) {
            currentCoroutineContext().ensureActive()
            val attempt = try {
                val raw = provider.translate(apiKey, model, requestPayload, level).getOrThrow()
                Result.success(parseOutput(raw))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (attempt.isSuccess) return attempt.getOrThrow()

            val error = attempt.exceptionOrNull()!!
            lastError = error

            val nextLevel = sequence.getOrNull(index + 1)
            if (nextLevel != null && shouldEscalate(error)) {
                onEscalate(level, nextLevel, error)
                continue
            }
            throw error
        }
        throw lastError ?: IllegalStateException("翻译失败")
    }

    internal fun shouldEscalate(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        val http = chain.filterIsInstance<HttpStatusException>().firstOrNull()
        if (http != null) {
            return http.status == 400 && isThinkingParameterError(http.message.orEmpty())
        }
        if (chain.any { it is IOException }) return false
        return true
    }

    private fun isThinkingParameterError(message: String): Boolean {
        val lower = message.lowercase()
        return THINKING_ERROR_KEYWORDS.any(lower::contains)
    }

    private val THINKING_ERROR_KEYWORDS = listOf(
        "thinking",
        "reasoning",
        "effort",
        "budget_tokens",
        "thinkingbudget",
        "thinkinglevel",
        "thinkingconfig",
        "output_config",
        "unsupported value",
        "unsupported_value",
        "cannot be disabled",
        "not_disableable",
    )
}
