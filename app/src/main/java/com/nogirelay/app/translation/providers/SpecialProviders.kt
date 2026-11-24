package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProtocol
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BaseAIProvider
import com.nogirelay.app.translation.HIGH_THINKING_BUDGET_TOKENS
import com.nogirelay.app.translation.IndexedSegmentTranslations
import com.nogirelay.app.translation.JsonOutputSupport
import com.nogirelay.app.translation.LOW_THINKING_BUDGET_TOKENS
import com.nogirelay.app.translation.TRANSLATION_MAX_OUTPUT_TOKENS
import com.nogirelay.app.translation.ThinkingLevel
import com.nogirelay.app.translation.resolveThinkingLevels
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface GeminiThinkingControl {
    data class Budget(val tokens: Int) : GeminiThinkingControl
    data class Level(val value: String) : GeminiThinkingControl
}

class GeminiProvider : BaseAIProvider() {
    override val type = AIProviderType.GEMINI
    override val protocol = AIProtocol.GEMINI_GENERATE_CONTENT
    override val baseUrl = "https://generativelanguage.googleapis.com"
    override val modelsEndpoint = "/v1beta/models?pageSize=1000"

    override fun buildHeaders(apiKey: String) = mapOf(
        "x-goog-api-key" to apiKey,
        "Content-Type" to "application/json",
    )

    internal fun thinkingControlFor(model: String, thinking: ThinkingLevel): GeminiThinkingControl? {
        val id = model.lowercase()
        return when {
            id.contains("gemini-2.5-pro") -> GeminiThinkingControl.Budget(
                when (thinking) {
                    ThinkingLevel.NONE -> 128
                    ThinkingLevel.LOW -> LOW_THINKING_BUDGET_TOKENS
                    ThinkingLevel.HIGH -> HIGH_THINKING_BUDGET_TOKENS
                },
            )
            id.contains("gemini-2.5") || id.contains("robotics-er-1.6") -> GeminiThinkingControl.Budget(
                when (thinking) {
                    ThinkingLevel.NONE -> 0
                    ThinkingLevel.LOW -> LOW_THINKING_BUDGET_TOKENS
                    ThinkingLevel.HIGH -> HIGH_THINKING_BUDGET_TOKENS
                },
            )
            id.startsWith("gemini-3") -> {
                val supportsMinimal = !(id.contains("pro") || id.contains("gemini-3.7") || id.contains("gemini-3.8"))
                val level = when (thinking) {
                    ThinkingLevel.NONE -> if (supportsMinimal) "minimal" else "low"
                    ThinkingLevel.LOW -> "low"
                    ThinkingLevel.HIGH -> "high"
                }
                GeminiThinkingControl.Level(level)
            }
            id.startsWith("gemini-4") -> GeminiThinkingControl.Level(
                when (thinking) {
                    ThinkingLevel.NONE, ThinkingLevel.LOW -> "low"
                    ThinkingLevel.HIGH -> "high"
                },
            )
            else -> null
        }
    }

    override fun thinkingLevelsFor(model: String): List<ThinkingLevel> =
        resolveThinkingLevels { thinkingControlFor(model, it) }

    override fun buildTranslateRequest(model: String, text: String, thinking: ThinkingLevel): String =
        JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", createPrompt(text)))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", TRANSLATION_MAX_OUTPUT_TOKENS)

                if (jsonOutputSupport(model).isSupported) {
                    put("responseMimeType", "application/json")
                    put("responseSchema", IndexedSegmentTranslations.geminiSchema)
                }
                when (val control = thinkingControlFor(model, thinking)) {
                    is GeminiThinkingControl.Budget ->
                        put("thinkingConfig", JSONObject().put("thinkingBudget", control.tokens))
                    is GeminiThinkingControl.Level ->
                        put("thinkingConfig", JSONObject().put("thinkingLevel", control.value))
                    null -> Unit
                }
            })
        }.toString()

    override fun parseTranslateResponse(response: String): String {
        val parts = JSONObject(response)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        return (0 until parts.length())
            .mapNotNull { index ->
                val part = parts.optJSONObject(index) ?: return@mapNotNull null
                if (part.optBoolean("thought", false)) return@mapNotNull null
                part.optString("text").takeIf(String::isNotEmpty)
            }
            .joinToString(separator = "")
            .trim()
            .ifEmpty { throw IllegalStateException("Gemini 响应中没有文本内容") }
    }

    override fun parseModelsResponse(response: String): List<AIModel> {
        val models = JSONObject(response).getJSONArray("models")
        return (0 until models.length()).mapNotNull { index ->
            val model = models.getJSONObject(index)
            val methods = model.optJSONArray("supportedGenerationMethods")
            val supportsGenerateContent = methods != null &&
                (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!supportsGenerateContent) return@mapNotNull null

            val id = model.getString("name").substringAfter("models/")
            AIModel(id, model.optString("displayName", id))
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models

    override fun jsonOutputSupport(model: String): JsonOutputSupport {
        val id = model.lowercase()
        val isTextGemini = (id.contains("gemini-2.5") || id.startsWith("gemini-3") || id.startsWith("gemini-4")) &&
            NON_TEXT_MARKERS.none(id::contains)
        return if (isTextGemini) JsonOutputSupport.JSON_SCHEMA else JsonOutputSupport.NONE
    }

    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> =
        TranslationNetworkHelper.fetchModels(this, apiKey)

    override suspend fun translate(
        apiKey: String,
        model: String,
        text: String,
        thinking: ThinkingLevel,
    ): Result<String> = TranslationNetworkHelper.translate(
        this,
        apiKey,
        model,
        text,
        "$baseUrl/v1beta/models/$model:generateContent",
        thinking,
    )
}

private val NON_TEXT_MARKERS = listOf("image", "audio", "live", "transcribe", "robotics", "embedding", "tts")

class GrokProvider : OpenAIResponsesProvider() {
    override val type = AIProviderType.GROK
    override val baseUrl = "https://api.x.ai"
    override val modelsEndpoint = "/v1/models"
    override val responsesEndpoint = "$baseUrl/v1/responses"

    override fun reasoningEffortFor(model: String, thinking: ThinkingLevel): String? {
        if (!model.startsWith("grok-4", ignoreCase = true)) return null
        return when (thinking) {
            ThinkingLevel.NONE, ThinkingLevel.LOW -> "low"
            ThinkingLevel.HIGH -> "high"
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> =
        models.filter { it.id.startsWith("grok-", ignoreCase = true) }
}
