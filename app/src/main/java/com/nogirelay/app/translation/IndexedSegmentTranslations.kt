package com.nogirelay.app.translation

import org.json.JSONArray
import org.json.JSONObject

/**
 * 编码并解析整篇内容翻译请求的片段列表。
 *
 * 请求始终携带完整的原文，以及每个片段及其索引，并要求模型返回相同
 * 的索引。只有当每个索引都恰好出现一次时才接受该译文，因此缺失
 * 或重复的片段会被检测出来，而不会静默地导致其余文本错位。
 * 片段绝不会作为独立请求发送：模型始终一次性看到并
 * 翻译整篇内容。
 *
 * 规范响应为 `{"segments":[{"index":0,"text":"..."}]}`：强制结构化输出的
 * 提供方（OpenAI Responses、Anthropic Messages）要求其 JSON schema
 * 根节点是一个对象。裸数组仍然被接受，因此只能遵循提示词的提供方也能
 * 继续工作。
 */
internal object IndexedSegmentTranslations {
    private const val SEGMENTS_KEY = "segments"

    /**
     * 规范响应的 JSON Schema，发送给支持结构化输出的提供方。按照严格模式的
     * 要求，每个对象都设置 `additionalProperties: false`，并在 `required`
     * 中列出其全部属性。
     */
    val schema: JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray().put(SEGMENTS_KEY))
        .put(
            "properties",
            JSONObject().put(
                SEGMENTS_KEY,
                JSONObject()
                    .put("type", "array")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "object")
                            .put("additionalProperties", false)
                            .put("required", JSONArray().put("index").put("text"))
                            .put(
                                "properties",
                                JSONObject()
                                    .put("index", JSONObject().put("type", "integer"))
                                    .put("text", JSONObject().put("type", "string")),
                            ),
                    ),
            ),
        )

    /**
     * 同一 schema 的 Gemini `responseSchema` 方言：类型使用大写，且没有
     * `additionalProperties`，因为 Gemini 会拒绝后者。
     */
    val geminiSchema: JSONObject = JSONObject()
        .put("type", "OBJECT")
        .put("required", JSONArray().put(SEGMENTS_KEY))
        .put(
            "properties",
            JSONObject().put(
                SEGMENTS_KEY,
                JSONObject()
                    .put("type", "ARRAY")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "OBJECT")
                            .put("required", JSONArray().put("index").put("text"))
                            .put(
                                "properties",
                                JSONObject()
                                    .put("index", JSONObject().put("type", "INTEGER"))
                                    .put("text", JSONObject().put("type", "STRING")),
                            ),
                    ),
            ),
        )

    fun encode(segments: List<String>): JSONArray = JSONArray().apply {
        segments.forEachIndexed { index, text ->
            put(JSONObject().put("index", index).put("text", text))
        }
    }

    fun parse(modelOutput: String, count: Int): List<String> {
        val elements = elementsOf(unwrapCodeFence(modelOutput))
        val indexed = elements.length() > 0 &&
            (0 until elements.length()).all { elements.optJSONObject(it) != null }
        return if (indexed) parseIndexed(elements, count) else parsePositional(elements, count)
    }

    /** 接受规范的 `{"segments":[...]}` 对象以及旧版的裸数组。 */
    private fun elementsOf(payload: String): JSONArray {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return JSONArray(trimmed)
        return JSONObject(trimmed).optJSONArray(SEGMENTS_KEY)
            ?: throw IllegalArgumentException("JSON 对象缺少 $SEGMENTS_KEY 数组")
    }

    private fun parseIndexed(elements: JSONArray, count: Int): List<String> {
        val translated = arrayOfNulls<String>(count)
        val outOfRange = mutableListOf<Int>()
        val duplicated = mutableListOf<Int>()

        for (position in 0 until elements.length()) {
            val element = elements.getJSONObject(position)
            val text = element.opt("text")
            require(text is String) { "译文片段缺少 text 字符串：$element" }
            require('\n' !in text && '\r' !in text) {
                "译文片段 ${element.optInt("index", -1) + 1} 包含额外换行"
            }

            val index = element.optInt("index", -1)
            when {
                index !in 0 until count -> outOfRange += index
                translated[index] != null -> duplicated += index
                else -> translated[index] = text
            }
        }

        val missing = (0 until count).filter { translated[it] == null }
        require(missing.isEmpty() && duplicated.isEmpty() && outOfRange.isEmpty()) {
            mismatchMessage(count, elements.length(), missing, duplicated, outOfRange)
        }
        return translated.mapIndexed { index, value ->
            requireNotNull(value) { "译文片段索引缺失：$index" }
        }
    }

    /** 旧版按位置的数组：仍然要求片段数量完全一致。 */
    private fun parsePositional(elements: JSONArray, count: Int): List<String> {
        require(elements.length() == count) {
            mismatchMessage(count, elements.length(), emptyList(), emptyList(), emptyList())
        }
        return (0 until count).map { index ->
            require(elements.get(index) is String) { "译文片段 ${index + 1} 不是字符串" }
            elements.getString(index).also { value ->
                require('\n' !in value && '\r' !in value) { "译文片段 ${index + 1} 包含额外换行" }
            }
        }
    }

    private fun mismatchMessage(
        count: Int,
        received: Int,
        missing: List<Int>,
        duplicated: List<Int>,
        outOfRange: List<Int>,
    ): String = buildString {
        append("译文片段数量不匹配：需要 $count 个，实际收到 $received 个")
        if (missing.isNotEmpty()) append("；缺失索引 ${missing.joinToString(",")}")
        if (duplicated.isNotEmpty()) append("；重复索引 ${duplicated.joinToString(",")}")
        if (outOfRange.isNotEmpty()) append("；越界索引 ${outOfRange.joinToString(",")}")
    }

    private fun unwrapCodeFence(value: String): String {
        val trimmed = value.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith("```")) return trimmed

        val contentStart = trimmed.indexOf('\n')
        val contentEnd = trimmed.lastIndexOf("```")
        require(contentStart >= 0 && contentEnd > contentStart) { "模型返回了不完整的 JSON 代码块" }
        return trimmed.substring(contentStart + 1, contentEnd).trim()
    }
}
