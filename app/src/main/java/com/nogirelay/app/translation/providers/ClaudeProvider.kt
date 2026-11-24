package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.HIGH_THINKING_BUDGET_TOKENS
import com.nogirelay.app.translation.JsonOutputSupport
import com.nogirelay.app.translation.LOW_THINKING_BUDGET_TOKENS
import com.nogirelay.app.translation.ThinkingLevel
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

    override fun reasoningControlFor(model: String, thinking: ThinkingLevel): AnthropicReasoningControl? {
        val id = model.lowercase()
        return when {
            id.contains("fable") || id.contains("mythos") -> {
                val effort = when (thinking) {
                    ThinkingLevel.NONE, ThinkingLevel.LOW -> "low"
                    ThinkingLevel.HIGH -> "high"
                }
                AnthropicReasoningControl(effort = effort)
            }
            Regex("claude-(opus|sonnet)-5").containsMatchIn(id) -> when (thinking) {
                ThinkingLevel.NONE -> AnthropicReasoningControl(thinkingType = "disabled", effort = "low")
                ThinkingLevel.LOW -> AnthropicReasoningControl(thinkingType = "adaptive", effort = "low")
                ThinkingLevel.HIGH -> AnthropicReasoningControl(thinkingType = "adaptive", effort = "high")
            }
            Regex("claude-(opus|sonnet)-4-[6-9]").containsMatchIn(id) -> when (thinking) {
                ThinkingLevel.NONE -> AnthropicReasoningControl(thinkingType = "disabled")
                ThinkingLevel.LOW -> AnthropicReasoningControl(thinkingType = "adaptive", effort = "low")
                ThinkingLevel.HIGH -> AnthropicReasoningControl(thinkingType = "adaptive", effort = "high")
            }
            Regex("claude-(opus|sonnet|haiku)-4-5|claude-(opus|sonnet)-4(-|$)|claude-3-7-sonnet").containsMatchIn(id) ->
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
            else -> null
        }
    }

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

        val SUPPORTED_PATTERN = Regex(
            "^claude-(fable-5|mythos-5|mythos-preview|opus-5|opus-4-[5-8]|sonnet-5|sonnet-4-[56]|haiku-4-5)",
        )
    }
}
