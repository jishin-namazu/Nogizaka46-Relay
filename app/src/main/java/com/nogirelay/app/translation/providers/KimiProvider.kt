package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.HIGH_THINKING_BUDGET_TOKENS
import com.nogirelay.app.translation.JsonOutputSupport
import com.nogirelay.app.translation.LOW_THINKING_BUDGET_TOKENS
import com.nogirelay.app.translation.ThinkingLevel
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

    override fun reasoningControlFor(model: String, thinking: ThinkingLevel): AnthropicReasoningControl {
        val id = model.lowercase()
        return if (id.contains("thinking") || id.startsWith("kimi-k3")) {
            val effort = when (thinking) {
                ThinkingLevel.NONE, ThinkingLevel.LOW -> "low"
                ThinkingLevel.HIGH -> "high"
            }
            AnthropicReasoningControl(effort = effort)
        } else {
            when (thinking) {
                ThinkingLevel.NONE -> AnthropicReasoningControl(thinkingType = "disabled")
                ThinkingLevel.LOW -> AnthropicReasoningControl(
                    thinkingType = "enabled",
                    budgetTokens = LOW_THINKING_BUDGET_TOKENS,
                )
                ThinkingLevel.HIGH -> AnthropicReasoningControl(
                    thinkingType = "enabled",
                    budgetTokens = HIGH_THINKING_BUDGET_TOKENS,
                )
            }
        }
    }

    override fun jsonOutputSupport(model: String) = JsonOutputSupport.JSON_SCHEMA

    override fun applyJsonOutputControls(request: JSONObject, model: String) {
        mergeJsonOutputFormat(request)
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.startsWith("kimi-") || id.startsWith("moonshot-")
    }
}
