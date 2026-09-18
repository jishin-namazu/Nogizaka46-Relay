package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.JsonOutputSupport
import org.json.JSONObject

class KimiProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.KIMI
    override val baseUrl = "https://api.moonshot.cn"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    private fun buildMessagesHeaders(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json",
    )

    override fun buildModelHeaders(apiKey: String) = buildMessagesHeaders(apiKey)

    override fun buildHeaders(apiKey: String) = buildMessagesHeaders(apiKey)

    override fun applyReasoningControls(request: JSONObject, model: String) {
        val id = model.lowercase()
        if (id.contains("thinking") || id.startsWith("kimi-k3")) {
            // 这些模型无法关闭 Thinking；使用最低的自适应 effort。
            request.put("output_config", JSONObject().put("effort", "low"))
        } else {
            request.put("thinking", JSONObject().put("type", "disabled"))
        }
    }

    /**
     * Moonshot 在本 provider 调用的 `/anthropic/v1/messages` 端点的 OpenAPI 描述中记录了
     * `output_config.format`：响应随后会严格遵循给定的 JSON Schema。它与 `effort`
     * 共用 `output_config`，因此合并已有值而不是覆盖。
     */
    override fun jsonOutputSupport(model: String) = JsonOutputSupport.JSON_SCHEMA

    override fun applyJsonOutputControls(request: JSONObject, model: String) {
        mergeJsonOutputFormat(request)
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.startsWith("kimi-") || id.startsWith("moonshot-")
    }
}
