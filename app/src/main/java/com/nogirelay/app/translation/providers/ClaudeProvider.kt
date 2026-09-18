package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.JsonOutputSupport
import org.json.JSONObject

class ClaudeProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.CLAUDE
    override val baseUrl = "https://api.anthropic.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/v1/messages"
    override val includeAnthropicVersion = true

    override fun buildModelHeaders(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json",
    )

    override fun applyReasoningControls(request: JSONObject, model: String) {
        val id = model.lowercase()
        when {
            id.contains("fable") || id.contains("mythos") -> {
                request.put("output_config", JSONObject().put("effort", "low"))
            }
            Regex("claude-(opus|sonnet)-5").containsMatchIn(id) -> {
                request.put("thinking", JSONObject().put("type", "disabled"))
                request.put("output_config", JSONObject().put("effort", "low"))
            }
        }
    }

    /**
     * Anthropic 正式可用的结构化输出位于 `output_config.format`，它与推理的 `effort`
     * 字段共享同一个对象，因此会合并已有值而不是覆盖它。只有 Anthropic 文档中
     * 声明支持的模型才会收到该参数；其他模型会导致请求以 400 失败。
     */
    override fun jsonOutputSupport(model: String): JsonOutputSupport =
        if (SUPPORTED_PATTERN.containsMatchIn(model.lowercase())) {
            JsonOutputSupport.JSON_SCHEMA
        } else {
            JsonOutputSupport.NONE
        }

    override fun applyJsonOutputControls(request: JSONObject, model: String) {
        if (!jsonOutputSupport(model).isSupported) return
        mergeJsonOutputFormat(request)
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> =
        models.filter { it.id.startsWith("claude-", ignoreCase = true) }

    private companion object {
        /**
         * Anthropic 结构化输出文档中“Supported models”下列出的模型。
         * 前缀匹配让同一家族较新的快照继续可用，而全新的
         * 家族则直接回退到仅用提示词的约定。
         */
        val SUPPORTED_PATTERN = Regex(
            "^claude-(fable-5|mythos-5|mythos-preview|opus-5|opus-4-[5-8]|sonnet-5|sonnet-4-[56]|haiku-4-5)",
        )
    }
}
