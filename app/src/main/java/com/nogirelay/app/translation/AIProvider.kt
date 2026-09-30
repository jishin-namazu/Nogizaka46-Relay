package com.nogirelay.app.translation

import org.json.JSONObject

data class AIModel(
    val id: String,
    val displayName: String,
)

const val TRANSLATION_MAX_OUTPUT_TOKENS = 32768

enum class AIProtocol {
    MESSAGES,
    RESPONSES,
    GEMINI_GENERATE_CONTENT,
}

enum class JsonOutputSupport(val label: String) {

    NONE("提示词约束"),

    JSON_MODE("JSON 模式"),

    JSON_SCHEMA("JSON Schema");

    val isSupported: Boolean get() = this != NONE
}

enum class AIProviderType(val displayName: String, val supportsStructuredOutput: Boolean) {
    OPENAI("OpenAI", true),
    KIMI("Kimi (Moonshot)", true),
    CLAUDE("Claude (Anthropic)", true),
    DEEPSEEK("DeepSeek", false),
    GLM("智谱 GLM", false),
    GEMINI("Google Gemini", true),
    QWEN("通义千问", true),
    GROK("Grok (xAI)", false),
    MINIMAX("MiniMax", false),
    MIMO("小米 MiMo", false),
    HUNYUAN("腾讯混元", false);
}

interface AIProvider {
    val type: AIProviderType
    val protocol: AIProtocol
    val baseUrl: String
    val modelsEndpoint: String

    suspend fun fetchModels(apiKey: String): Result<List<AIModel>>
    suspend fun translate(apiKey: String, model: String, text: String): Result<String>

    fun buildModelHeaders(apiKey: String): Map<String, String>
    fun buildHeaders(apiKey: String): Map<String, String>
    fun buildTranslateRequest(model: String, text: String): String
    fun parseTranslateResponse(response: String): String
    fun parseModelsResponse(response: String): List<AIModel>
    fun filterChatModels(models: List<AIModel>): List<AIModel>

    @Suppress("UNUSED_PARAMETER")
    fun jsonOutputSupport(model: String): JsonOutputSupport = JsonOutputSupport.NONE
}

abstract class BaseAIProvider : AIProvider {
    override fun buildModelHeaders(apiKey: String): Map<String, String> = buildHeaders(apiKey)

    @Suppress("UNUSED_PARAMETER")
    protected open fun applyJsonOutputControls(request: JSONObject, model: String) = Unit

    protected fun mergeJsonOutputFormat(request: JSONObject) {
        val outputConfig = request.optJSONObject("output_config") ?: JSONObject()
        outputConfig.put(
            "format",
            JSONObject()
                .put("type", "json_schema")
                .put("schema", IndexedSegmentTranslations.schema),
        )
        request.put("output_config", outputConfig)
    }

    protected fun createPrompt(text: String): String {
        return """
            你将收到一份完整日语内容（消息或 BLOG），以及按原文顺序编号的文本片段。请结合完整内容的上下文，一次性把所有片段一起翻译成简体中文。

            要求：
            1. 人名必须保持原文，不得翻译、音译、改写或替换。
            2. 对于不应翻译的内容（例如专有名词），请保留原文。原文中的 "%%%" 是用户昵称占位符，必须原样保留（三个百分号不变），不得翻译、替换、删除或改写成人名。
            3. 必须一次翻译整份内容，不得只翻译部分片段，也不得把内容拆成多次请求。
            4. 只能输出一个 JSON 对象，且只包含一个键 "segments"，格式如下：
            {"segments":[{"index":0,"text":"译文"},{"index":1,"text":"译文"}]}
            5. "segments" 的元素个数必须与输入片段数完全一致；每个输入片段恰好对应一个元素，不得合并、拆分、遗漏、重复或增加。
            6. 每个元素的 index 必须与输入片段给出的 index 相同，每个 index 恰好出现一次。
            7. 每个 text 必须是字符串，且内部不得包含换行符。
            8. 保持原文的语气和情感。
            9. 直接输出这个 JSON 对象本身，不要输出 Markdown、代码块、前后缀文字或任何解释，也不要添加 index、text 以外的字段。

            日语内容：
            $text
        """.trimIndent()
    }
}
