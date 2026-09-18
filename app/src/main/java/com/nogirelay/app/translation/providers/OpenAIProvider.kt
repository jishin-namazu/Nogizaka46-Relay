package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.IndexedSegmentTranslations
import com.nogirelay.app.translation.JsonOutputSupport
import org.json.JSONObject

class OpenAIProvider : OpenAIResponsesProvider() {
    override val type = AIProviderType.OPENAI
    override val baseUrl = "https://api.openai.com"
    override val modelsEndpoint = "/v1/models"
    override val responsesEndpoint = "$baseUrl/v1/responses"

    override fun applyReasoningControls(request: JSONObject, model: String) {
        val id = model.lowercase()
        val effort = when {
            id.startsWith("gpt-6") || Regex("^gpt-5\\.[1-9]").containsMatchIn(id) -> "none"
            id.startsWith("gpt-5") -> "minimal"
            Regex("^o\\d").containsMatchIn(id) -> "low"
            else -> null
        }
        effort?.let { request.put("reasoning", JSONObject().put("effort", it)) }
    }

    /**
     * OpenAI Responses 结构化输出：带严格 JSON Schema 的 `text.format`。不支持的模型会
     * 返回 HTTP 400（"text.format of type json_schema is not supported with model version ..."），
     * 因此只向有文档说明的模型系列发送该字段。
     */
    override fun jsonOutputSupport(model: String): JsonOutputSupport =
        if (supportsStructuredOutput(model)) JsonOutputSupport.JSON_SCHEMA else JsonOutputSupport.NONE

    override fun applyJsonOutputControls(request: JSONObject, model: String) {
        if (!jsonOutputSupport(model).isSupported) return
        request.put(
            "text",
            JSONObject().put(
                "format",
                JSONObject()
                    .put("type", "json_schema")
                    .put("name", "translated_segments")
                    .put("schema", IndexedSegmentTranslations.schema)
                    .put("strict", true),
            ),
        )
    }

    private fun supportsStructuredOutput(model: String): Boolean {
        val id = model.lowercase()
        if (id.contains("audio") || id.contains("realtime")) return false
        // 2024-05-13 的 GPT-4o 快照早于结构化输出；之后的快照和
        // 不带日期的别名则支持它们。
        val gpt4oSnapshot = Regex("^gpt-4o-(\\d{4}-\\d{2}-\\d{2})$").find(id)?.groupValues?.get(1)
        return when {
            id.startsWith("gpt-6") || id.startsWith("gpt-5") -> true
            id.startsWith("gpt-4.1") || id.startsWith("gpt-4o-mini") -> true
            id.startsWith("gpt-4o") -> gpt4oSnapshot == null || gpt4oSnapshot >= "2024-08-06"
            id.startsWith("codex-mini") -> true
            Regex("^o[134](-|$)").containsMatchIn(id) ->
                !id.contains("preview") && !id.startsWith("o1-mini")
            else -> false
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        val textFamily = id.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(id)
        val nonTextFamily = listOf(
            "embedding",
            "whisper",
            "transcribe",
            "tts",
            "audio",
            "realtime",
            "dall-e",
            "image",
            "moderation",
        ).any(id::contains)
        textFamily && !nonTextFamily
    }
}
