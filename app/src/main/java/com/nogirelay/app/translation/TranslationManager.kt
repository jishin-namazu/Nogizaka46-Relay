package com.nogirelay.app.translation

import android.content.Context
import android.util.Log
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.AppSettings
import com.nogirelay.app.data.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object TranslationManager {
    private const val TAG = "NogiTranslation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val requestSlots = Semaphore(3)

    /** 数据库会把它限制到自身的最大值，因此一次批量处理会清空整个积压。 */
    private const val BULK_LIMIT = Int.MAX_VALUE

    fun enqueue(context: Context) = enqueueAfterSync(context)

    /**
     * 自动翻译入口：同步完成、App 启动、推送到达时调用。
     *
     * [newIds] 是本次刚写入的消息，永远会被翻译；历史积压只有在用户打开"消息全量翻译"
     * （默认关闭）时才会一起翻，所以关掉开关后自动流程不会再消耗历史未翻译内容。
     */
    fun enqueueAfterSync(context: Context, newIds: Collection<String> = emptyList()) {
        val appContext = context.applicationContext
        scope.launch {
            AppGraph.initialize(appContext)
            val settings = AppGraph.settings.read()
            if (!isConfigured(settings)) return@launch
            if (newIds.isNotEmpty()) {
                val fresh = runCatching { AppGraph.database.pendingTranslationsByIds(newIds) }
                    .onFailure { Log.w(TAG, "Fresh message lookup failed", it) }
                    .getOrNull()
                if (fresh != null) translateAll(settings, fresh)
            }
            if (settings.messageFullTranslation) {
                val backlog = runCatching { AppGraph.database.pendingTranslations(BULK_LIMIT) }
                    .onFailure { Log.w(TAG, "Translation backlog lookup failed", it) }
                    .getOrNull()
                if (backlog != null) translateAll(settings, backlog)
            }
        }
    }

    /** 只翻这些 id：单条"重新翻译"用，不受全量开关影响。 */
    fun enqueueIds(context: Context, ids: Collection<String>) {
        val appContext = context.applicationContext
        scope.launch {
            AppGraph.initialize(appContext)
            val settings = AppGraph.settings.read()
            if (!isConfigured(settings)) return@launch
            val pending = runCatching { AppGraph.database.pendingTranslationsByIds(ids) }.getOrNull()
            if (pending != null) translateAll(settings, pending)
        }
    }

    /**
     * 重新翻译整个本地库。用于在译文不再内嵌设备专属昵称之后执行一次，
     * 使已有记录改用可移植的 "%%%" 占位符形式。
     */
    fun retranslateEverything(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            AppGraph.initialize(appContext)
            runCatching {
                AppGraph.database.markAllMessagesForRetranslation()
                AppGraph.database.markAllBlogsForRetranslation()
            }
            resetRetries()
            BlogTranslationManager.resetRetries()
            val settings = AppGraph.settings.read()
            if (isConfigured(settings)) {
                val backlog = runCatching { AppGraph.database.pendingTranslations(BULK_LIMIT) }.getOrNull()
                if (backlog != null) translateAll(settings, backlog)
            }
            // "重新翻译全部"是明确的手动动作，必须无视全量开关。
            BlogTranslationManager.enqueueAllPending(appContext, BULK_LIMIT)
        }
    }

    private fun isConfigured(settings: AppSettings): Boolean {
        Log.d(TAG, "Translation enqueue called: enabled=${settings.translationEnabled}, hasApiKey=${settings.aiApiKey.isNotBlank()}, hasModel=${settings.aiModel.isNotBlank()}")
        return settings.translationEnabled && settings.aiApiKey.isNotBlank() && settings.aiModel.isNotBlank()
    }

    /** 把这一批消息派发到请求池；并发仍由 [requestSlots] 控制。 */
    private fun translateAll(settings: AppSettings, pending: List<RelayMessage>) {
        val provider = runCatching {
            AIProviderFactory.getProvider(settings.aiProvider)
        }.getOrElse { error ->
            Log.w(TAG, "Invalid AI provider configuration: ${error.message}", error)
            return
        }
        val model = settings.aiModel.trim()

        Log.d(TAG, "Found ${pending.size} pending translations")
        val now = System.currentTimeMillis()
        pending.forEach { message ->
            if ((retryAfter[message.id] ?: 0L) > now) {
                Log.d(TAG, "Message ${message.id} waiting for retry")
                return@forEach
            }
            if (!inFlight.add(message.id)) {
                Log.d(TAG, "Message ${message.id} already in flight")
                return@forEach
            }
            Log.d(TAG, "Starting translation for message ${message.id}")
            scope.launch {
                try {
                    requestSlots.withPermit {
                        // "%%%" 占位符在翻译过程中受到保护，只在渲染时解析，
                        // 因此已存储的译文在任何设备（以及任何导出的存档）上都保持正确，
                        // 并能经受昵称变更。
                        val text = message.text.orEmpty()
                        if (!shouldTranslate(text)) {
                            Log.d(TAG, "Message ${message.id} skipped (shouldTranslate=false)")
                            AppGraph.database.saveTranslation(message.id, null)
                        } else {
                            Log.d(TAG, "Translating message ${message.id}: $text")
                            val layout = TranslationLayout.from(text)
                            val result = provider
                                .translate(settings.aiApiKey, model, layout.requestPayload)
                                .mapCatching(layout::restore)
                            result.onSuccess { translation ->
                                Log.d(TAG, "Translation success for ${message.id}")
                                AppGraph.database.saveTranslation(message.id, translation.takeIf { it.isNotBlank() })
                                AppGraph.notifyDataChanged()
                                retryAfter.remove(message.id)
                                retryCount.remove(message.id)
                            }.onFailure { error ->
                                throw error
                            }
                        }
                    }
                } catch (error: Exception) {
                    val attempts = (retryCount.merge(message.id, 1, Int::plus) ?: 1).coerceAtMost(8)
                    val delayMs = (5_000L * (1L shl (attempts - 1))).coerceAtMost(5 * 60_000L)
                    retryAfter[message.id] = System.currentTimeMillis() + delayMs
                    Log.w(TAG, "Translation failed for ${message.id}; retrying in ${delayMs / 1000}s: ${error.message}", error)
                } finally {
                    inFlight.remove(message.id)
                }
            }
        }
    }

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
