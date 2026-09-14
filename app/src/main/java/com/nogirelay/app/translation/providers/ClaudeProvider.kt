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
     * Anthropic's generally available structured outputs live in `output_config.format`, which
     * shares the object with the reasoning `effort` field, so the existing value is merged
     * instead of overwritten. Only the models Anthropic documents as supported are sent the
     * parameter; anything else would fail the request with a 400.
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
         * Models listed under "Supported models" in Anthropic's structured outputs documentation.
         * Prefix matching keeps newer snapshots of the same family working while a brand new
         * family simply falls back to the prompt-only contract.
         */
        val SUPPORTED_PATTERN = Regex(
            "^claude-(fable-5|mythos-5|mythos-preview|opus-5|opus-4-[5-8]|sonnet-5|sonnet-4-[56]|haiku-4-5)",
        )
    }
}
