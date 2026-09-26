package com.nogirelay.app.translation.providers

import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.IndexedSegmentTranslations
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
            // 这些模型始终会思考；使用 Anthropic 格式端点可接受的最低思考强度。
            request.put("output_config", JSONObject().put("effort", "low"))
        } else {
            // DeepSeek 默认开启思考，且不把 Anthropic 格式的 effort 字段当作开关，
            // 因此必须显式禁用思考。
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
        // 这些模型默认开启思考；Anthropic 格式的开关是 thinking.type=disabled。
        val configurable = (
            (id.startsWith("qwen3") && !id.contains("coder")) ||
                id.startsWith("qwen-plus") ||
                id.startsWith("qwen-flash")
            ) && !id.contains("thinking")
        if (configurable) request.put("thinking", JSONObject().put("type", "disabled"))
    }

    /**
     * Alibaba Model Studio 在与此处相同的 Anthropic 兼容端点上提供了 `output_config.format`
     * 的文档。qwen3.7/3.8 会严格遵循 schema；其他所有 qwen 模型则回退到普通 JSON 模式，该模式
     * 仍能保证返回可解析的文档。该回退需要在提示词中包含 "JSON" 一词，而 createPrompt 始终
     * 包含它。
     */
    override fun jsonOutputSupport(model: String): JsonOutputSupport {
        val id = model.lowercase()
        return when {
            // Alibaba 仅针对 qwen3.7/3.8 系列记录了严格的 schema 遵循。
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
        // MiniMax-M3 默认关闭，并接受显式禁用；M2.x 无法关闭思考，
        // 因此在那里不发送该字段。
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
