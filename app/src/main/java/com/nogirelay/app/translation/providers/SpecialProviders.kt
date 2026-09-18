package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProtocol
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BaseAIProvider
import com.nogirelay.app.translation.IndexedSegmentTranslations
import com.nogirelay.app.translation.JsonOutputSupport
import com.nogirelay.app.translation.TRANSLATION_MAX_OUTPUT_TOKENS
import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider : BaseAIProvider() {
    override val type = AIProviderType.GEMINI
    override val protocol = AIProtocol.GEMINI_GENERATE_CONTENT
    override val baseUrl = "https://generativelanguage.googleapis.com"
    override val modelsEndpoint = "/v1beta/models?pageSize=1000"

    override fun buildHeaders(apiKey: String) = mapOf(
        "x-goog-api-key" to apiKey,
        "Content-Type" to "application/json",
    )

    override fun buildTranslateRequest(model: String, text: String): String =
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
                val id = model.lowercase()
                // 结构化输出仅针对文本类 Gemini 模型（2.5 和 3.x）有文档说明。图像、
                // 音频、Live、转写、机器人以及非 Gemini 模型要么拒绝 responseSchema，
                // 要么忽略它，因此它们只保留仅提示词的约定。
                if (jsonOutputSupport(model).isSupported) {
                    put("responseMimeType", "application/json")
                    put("responseSchema", IndexedSegmentTranslations.geminiSchema)
                }
                when {
                    id.contains("gemini-2.5-pro") -> {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 128))
                    }
                    id.contains("gemini-2.5") || id.contains("robotics-er-1.6") -> {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    }
                    id.startsWith("gemini-3") -> {
                        val level = if (id.contains("pro") || id.contains("gemini-3.7") || id.contains("gemini-3.8")) {
                            "low"
                        } else {
                            "minimal"
                        }
                        put("thinkingConfig", JSONObject().put("thinkingLevel", level))
                    }
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
            .mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf(String::isNotEmpty) }
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

    /**
     * 仅针对文本类 Gemini 模型（2.5 和 3.x）有文档说明。图像、音频、Live、转写、
     * 机器人以及非 Gemini 模型要么拒绝 `responseSchema`，要么忽略它。
     */
    override fun jsonOutputSupport(model: String): JsonOutputSupport {
        val id = model.lowercase()
        val isTextGemini = (id.contains("gemini-2.5") || id.startsWith("gemini-3")) &&
            NON_TEXT_MARKERS.none(id::contains)
        return if (isTextGemini) JsonOutputSupport.JSON_SCHEMA else JsonOutputSupport.NONE
    }

    override suspend fun fetchModels(apiKey: String): Result<List<AIModel>> =
        TranslationNetworkHelper.fetchModels(this, apiKey)

    override suspend fun translate(
        apiKey: String,
        model: String,
        text: String,
    ): Result<String> = TranslationNetworkHelper.translate(
        this,
        apiKey,
        model,
        text,
        "$baseUrl/v1beta/models/$model:generateContent",
    )
}

private val NON_TEXT_MARKERS = listOf("image", "audio", "live", "transcribe", "robotics", "embedding", "tts")

class GrokProvider : OpenAIResponsesProvider() {
    override val type = AIProviderType.GROK
    override val baseUrl = "https://api.x.ai"
    override val modelsEndpoint = "/v1/models"
    override val responsesEndpoint = "$baseUrl/v1/responses"

    override fun applyReasoningControls(request: JSONObject, model: String) {
        // Grok 的推理无法禁用；"low" 是文档中记录的最低思考强度。
        if (model.startsWith("grok-4", ignoreCase = true)) {
            request.put("reasoning", JSONObject().put("effort", "low"))
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> =
        models.filter { it.id.startsWith("grok-", ignoreCase = true) }
}
