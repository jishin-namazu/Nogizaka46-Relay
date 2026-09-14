package com.nogirelay.app.translation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes and parses the fragment list of a whole-content translation request.
 *
 * The request always carries the complete original text plus every fragment with its
 * index, and the model is asked to return the same indices. A translation is accepted
 * only when every index appears exactly once, so a missing or duplicated fragment is
 * detected instead of silently shifting the remaining text. Fragments are never sent
 * as separate requests: the model always sees and translates the whole content at once.
 *
 * The canonical response is `{"segments":[{"index":0,"text":"..."}]}`: the providers with
 * enforced structured outputs (OpenAI Responses, Anthropic Messages) need an object at the
 * root of their JSON schema. A bare array is still accepted, so providers that can only
 * follow the prompt keep working.
 */
internal object IndexedSegmentTranslations {
    private const val SEGMENTS_KEY = "segments"

    /**
     * JSON Schema of the canonical response, sent to providers that support structured
     * outputs. Every object sets `additionalProperties: false` and lists all of its
     * properties in `required`, as strict mode demands.
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
     * The same schema in Gemini's `responseSchema` dialect: upper-case types and no
     * `additionalProperties`, which Gemini rejects.
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

    /** Accepts the canonical `{"segments":[...]}` object and the legacy bare array. */
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

    /** Legacy positional array: still requires the exact fragment count. */
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
