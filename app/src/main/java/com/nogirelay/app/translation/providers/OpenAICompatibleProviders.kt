package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.JsonOutputSupport
import org.json.JSONObject

class DeepSeekProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.DEEPSEEK
    override val baseUrl = "https://api.deepseek.com"
    override val modelsEndpoint = "/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    override fun applyReasoningControls(request: JSONObject, model: String) {
        val id = model.lowercase()
        if (id.contains("reasoner") || id.contains("thinking") || id.contains("r1")) {
            request.put("output_config", JSONObject().put("effort", "low"))
        } else {
            request.put("thinking", JSONObject().put("type", "disabled"))
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter {
        it.id.startsWith("deepseek-", ignoreCase = true) &&
            !it.id.contains("embedding", ignoreCase = true)
    }
}

class GLMProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.GLM
    override val baseUrl = "https://open.bigmodel.cn"
    override val modelsEndpoint = "/api/paas/v4/models"
    override val messagesEndpoint = "$baseUrl/api/anthropic/v1/messages"

    override fun applyReasoningControls(request: JSONObject, model: String) {
        val id = model.lowercase()
        when {
            id.startsWith("glm-5.3") -> {
                request.put("thinking", JSONObject().put("type", "enabled"))
                request.put("output_config", JSONObject().put("effort", "low"))
            }
            Regex("^glm-(4\\.[5-9]|[5-9])").containsMatchIn(id) -> {
                request.put("thinking", JSONObject().put("type", "disabled"))
            }
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        (id.startsWith("glm-") || id.startsWith("chatglm")) &&
            listOf("embedding", "image", "tts", "asr").none(id::contains)
    }
}

class QwenProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.QWEN
    override val baseUrl = "https://dashscope.aliyuncs.com"
    override val modelsEndpoint = "/compatible-mode/v1/models"
    override val messagesEndpoint = "$baseUrl/apps/anthropic/v1/messages"

    override fun applyReasoningControls(request: JSONObject, model: String) {
        val id = model.lowercase()

        val configurable = (
            (id.startsWith("qwen3") && !id.contains("coder")) ||
                id.startsWith("qwen-plus") ||
                id.startsWith("qwen-flash")
            ) && !id.contains("thinking")
        if (configurable) request.put("thinking", JSONObject().put("type", "disabled"))
    }

    override fun jsonOutputSupport(model: String): JsonOutputSupport {
        val id = model.lowercase()
        return when {
            id.startsWith("qwen3.8") || id.startsWith("qwen3.7") -> JsonOutputSupport.JSON_SCHEMA
            id.contains("qwen") -> JsonOutputSupport.JSON_MODE
            else -> JsonOutputSupport.NONE
        }
    }

    override fun applyJsonOutputControls(request: JSONObject, model: String) {
        if (!jsonOutputSupport(model).isSupported) return
        mergeJsonOutputFormat(request)
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.contains("qwen") &&
            listOf("embedding", "audio", "tts", "asr", "image").none(id::contains)
    }
}

class MiniMaxProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.MINIMAX
    override val baseUrl = "https://api.minimaxi.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    override fun applyReasoningControls(request: JSONObject, model: String) {
        if (model.contains("MiniMax-M3", ignoreCase = true)) {
            request.put("thinking", JSONObject().put("type", "disabled"))
        }
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> =
        models.filter { it.id.startsWith("MiniMax-M", ignoreCase = true) }
}

class MiMoProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.MIMO
    override val baseUrl = "https://api.xiaomimimo.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/anthropic/v1/messages"

    override fun buildModelHeaders(apiKey: String) = mapOf(
        "api-key" to apiKey,
        "Content-Type" to "application/json",
    )

    override fun buildHeaders(apiKey: String) = buildModelHeaders(apiKey)

    override fun applyReasoningControls(request: JSONObject, model: String) {
        request.put("thinking", JSONObject().put("type", "disabled"))
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.startsWith("mimo-") && listOf("asr", "tts").none(id::contains)
    }
}

class HunYuanProvider : AnthropicMessagesProvider() {
    override val type = AIProviderType.HUNYUAN
    override val baseUrl = "https://tokenhub.tencentmaas.com"
    override val modelsEndpoint = "/v1/models"
    override val messagesEndpoint = "$baseUrl/v1/messages"

    override fun applyReasoningControls(request: JSONObject, model: String) {
        request.put("thinking", JSONObject().put("type", "disabled"))
    }

    override fun filterChatModels(models: List<AIModel>): List<AIModel> = models.filter { model ->
        val id = model.id.lowercase()
        id.startsWith("hy") || id.startsWith("hunyuan-")
    }
}
