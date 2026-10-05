package com.nogirelay.app.translation

import android.content.Context
import android.util.Log
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.AppSettings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object TranslationManager {
    private const val TAG = "NogiTranslation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val retryCount = ConcurrentHashMap<String, Int>()

    /** Messages skipped while [TranslationHealth] paused translation; resumed by [resumeAfterPause]. */
    private val deferred = ConcurrentHashMap.newKeySet<String>()

    private var bulkJob: kotlinx.coroutines.Job? = null
    private val queue = com.nogirelay.app.performance.BoundedWorkQueue<String>(
        scope, parallelism = 3, capacity = 24, key = { it },
        onFailure = { id, error ->
            TranslationHealth.recordFailure(error, AppGraph.settings.read())
            val attempts = (retryCount.merge(id, 1, Int::plus) ?: 1).coerceAtMost(8)
            retryAfter[id] = System.currentTimeMillis() + (5_000L * (1L shl (attempts - 1))).coerceAtMost(300_000L)
            Log.w(TAG, "Translation failed for $id", error)
        },
    ) { id ->
        val settings = AppGraph.settings.read()
        if (isConfigured(settings) && !TranslationHealth.canTranslate(settings)) {
            deferred += id
        } else if (isConfigured(settings)) {
            val message = AppGraph.messages.find(id)
            if (message != null && !message.translationDone && !message.isTestMessage) {
                val text = message.text.orEmpty()
                val translation = if (shouldTranslate(text)) {
                    val layout = TranslationLayout.from(text)
                    AIProviderFactory.getProvider(settings.aiProvider)
                        .translate(settings.aiApiKey, settings.aiModel.trim(), layout.requestPayload)
                        .mapCatching(layout::restore).getOrThrow().takeIf(String::isNotBlank)
                } else null
                AppGraph.messages.saveTranslation(id, translation)
                if (translation != null) TranslationHealth.recordSuccess()
                retryAfter.remove(id)
                retryCount.remove(id)
            }
        }
    }

    fun enqueue(context: Context) = enqueueAfterSync(context)

    fun enqueueAfterSync(context: Context, newIds: Collection<String> = emptyList()) {
        AppGraph.initialize(context.applicationContext)
        enqueueIds(context, newIds)
        if (AppGraph.settings.read().messageFullTranslation) enqueueBacklog(manual = false)
    }

    fun enqueueIds(context: Context, ids: Collection<String>) {
        AppGraph.initialize(context.applicationContext)
        if (ids.isEmpty()) return
        scope.launch {
            if (!isConfigured(AppGraph.settings.read())) return@launch
            ids.forEach { id ->
                if ((retryAfter[id] ?: 0L) <= System.currentTimeMillis()) queue.enqueue(id)
            }
        }
    }

    @Synchronized
    private fun enqueueBacklog(manual: Boolean) {
        if (bulkJob?.isActive == true) {
            if (!manual) return
            bulkJob?.cancel()
        }
        bulkJob = scope.launch {
            var afterId: String? = null
            while (true) {
                val settings = AppGraph.settings.read()
                if (!isConfigured(settings) || !manual && !settings.messageFullTranslation) break
                val ids = AppGraph.messages.pendingTranslationIds(afterId = afterId)
                if (ids.isEmpty()) break
                ids.forEach { id ->
                    if ((retryAfter[id] ?: 0L) <= System.currentTimeMillis()) queue.enqueue(id)
                }
                afterId = ids.last()
            }
        }
    }

    /** Re-queues what a translation pause skipped (after a settings change or a retry). */
    fun resumeAfterPause(context: Context) {
        resetRetries()
        val ids = deferred.toList()
        deferred.removeAll(ids.toSet())
        enqueueIds(context, ids)
        enqueue(context)
    }

    fun retranslateEverything(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            AppGraph.initialize(appContext)
            AppGraph.messages.markAllMessagesForRetranslation()
            AppGraph.blogs.markAllBlogsForRetranslation()
            resetRetries()
            BlogTranslationManager.resetRetries()
            enqueueBacklog(manual = true)
            BlogTranslationManager.enqueueAllPending(appContext)
        }
    }

    private fun isConfigured(settings: AppSettings): Boolean =
        settings.translationEnabled && settings.aiApiKey.isNotBlank() && settings.aiModel.isNotBlank()

    fun resetRetries() {
        retryAfter.clear()
        retryCount.clear()
    }

    suspend fun fetchAvailableModels(
        providerType: AIProviderType,
        apiKey: String,
    ): Result<List<AIModel>> {
        require(apiKey.isNotBlank()) { "请先填写 API Key" }
        val provider = runCatching {
            AIProviderFactory.getProvider(providerType)
        }.getOrElse { return Result.failure(it) }
        return provider.fetchModels(apiKey)
    }

    fun shouldTranslate(text: String?): Boolean {
        if (text.isNullOrBlank() || isPureEmojiOrSymbols(text)) return false
        var hasHan = false
        var hasKana = false
        text.codePoints().forEach { codePoint ->
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN -> hasHan = true
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> hasKana = true
                else -> Unit
            }
        }
        return hasHan || hasKana
    }

    private fun isPureEmojiOrSymbols(text: String): Boolean {
        var hasVisibleSymbol = false
        var hasLetterOrDigit = false
        text.codePoints().forEach { codePoint ->
            if (Character.isWhitespace(codePoint)) return@forEach
            val type = Character.getType(codePoint)
            when {
                Character.isLetterOrDigit(codePoint) -> hasLetterOrDigit = true
                type == Character.FORMAT.toInt() || type == Character.NON_SPACING_MARK.toInt() -> Unit
                type == Character.OTHER_SYMBOL.toInt() || type == Character.MATH_SYMBOL.toInt() -> hasVisibleSymbol = true
                type in punctuationTypes -> hasVisibleSymbol = true
                else -> hasLetterOrDigit = true
            }
        }
        return hasVisibleSymbol && !hasLetterOrDigit
    }

    private val punctuationTypes = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
    )
}
