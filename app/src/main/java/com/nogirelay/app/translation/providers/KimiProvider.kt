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
            // Thinking cannot be turned off on these models; use the lowest adaptive effort.
            request.put("output_config", JSONObject().put("effort", "low"))
        } else {
            request.put("thinking", JSONObject().put("type", "disabled"))
        }
    }

    /**
     * Moonshot documents `output_config.format` in the OpenAPI description of the
     * `/anthropic/v1/messages` endpoint this provider calls: the response then follows the given
     * JSON Schema strictly. It shares `output_config` with `effort`, so the existing value is
     * merged instead of overwritten.
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
