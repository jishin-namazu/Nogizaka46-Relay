package com.nogirelay.app.translation

import org.json.JSONObject

/**
 * 将可翻译文本与其原始空白和行分隔符分离。
 * 所有文本片段在一次请求中发送，以便模型保留完整上下文；
 * 格式在本地恢复，而不是依赖模型复现它。
 */
internal class TranslationLayout private constructor(
    source: String,
    private val parts: List<Part>,
    private val segments: List<String>,
) {
    val requestPayload: String = JSONObject()
        .put("full_text", source)
        .put("segments", IndexedSegmentTranslations.encode(segments))
        .toString()

    fun restore(modelOutput: String): String {
        val values = IndexedSegmentTranslations.parse(modelOutput, segments.size)

        return buildString {
            parts.forEach { part ->
                when (part) {
                    is Part.Literal -> append(part.value)
                    is Part.Translated -> {
                        append(part.prefix)
                        append(values[part.index])
                        append(part.suffix)
                    }
                }
            }
        }
    }

    private sealed interface Part {
        data class Literal(val value: String) : Part
        data class Translated(
            val index: Int,
            val prefix: String,
            val suffix: String,
        ) : Part
    }

    companion object {
        private val lineSeparator = Regex("\\r\\n|\\r|\\n")

        fun from(source: String): TranslationLayout {
            val parts = mutableListOf<Part>()
            val segments = mutableListOf<String>()
            var cursor = 0

            lineSeparator.findAll(source).forEach { match ->
                addTextPart(source.substring(cursor, match.range.first), parts, segments)
                parts += Part.Literal(match.value)
                cursor = match.range.last + 1
            }
            addTextPart(source.substring(cursor), parts, segments)

            return TranslationLayout(source, parts, segments)
        }

        private fun addTextPart(
            value: String,
            parts: MutableList<Part>,
            segments: MutableList<String>,
        ) {
            if (value.isBlank()) {
                if (value.isNotEmpty()) parts += Part.Literal(value)
                return
            }

            val coreStart = value.indexOfFirst { it != ' ' && it != '\t' }
            val coreEnd = value.indexOfLast { it != ' ' && it != '\t' } + 1
            val segmentIndex = segments.size
            segments += value.substring(coreStart, coreEnd)
            parts += Part.Translated(
                index = segmentIndex,
                prefix = value.substring(0, coreStart),
                suffix = value.substring(coreEnd),
            )
        }
    }
}
