package com.nogirelay.app.blog

import android.text.Html

sealed interface BlogContentBlock {
    data class Text(val value: String) : BlogContentBlock
    data class Image(val url: String) : BlogContentBlock
}

object BlogContentParser {
    private val imageTag = Regex("<img\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val imageSource = Regex("\\bsrc\\s*=\\s*(['\"])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun blocks(html: String): List<BlogContentBlock> {
        val result = mutableListOf<BlogContentBlock>()
        var cursor = 0
        imageTag.findAll(html).forEach { match ->
            addText(html.substring(cursor, match.range.first), result)
            imageSource.find(match.value)?.groupValues?.getOrNull(2)?.let(::officialUrl)?.let {
                result += BlogContentBlock.Image(it)
            }
            cursor = match.range.last + 1
        }
        addText(html.substring(cursor), result)
        return result
    }

    /**
     * 按出现顺序返回正文中每个 `<img>` 的目标 URL；`src` 为空或解析不出官方地址时返回 null，
     * 但仍占一个位置，使同一篇正文的前后两次解析能逐位对齐。归档导入据此把旧、新正文的
     * 图片一一配对，从而复用已下载的缓存。
     */
    fun imageUrlsInOrder(html: String): List<String?> =
        imageTag.findAll(html).map { match ->
            imageSource.find(match.value)?.groupValues?.getOrNull(2)?.let(::officialUrl)
        }.toList()

    fun plainText(blocks: List<BlogContentBlock>): String = blocks
        .filterIsInstance<BlogContentBlock.Text>()
        .map(BlogContentBlock.Text::value)
        .filter(String::isNotBlank)
        .joinToString("\n\n\n")

    fun paragraphs(value: String): List<String> = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\n{3,}"))
        .map { it.trim('\n') }
        .filter(String::isNotBlank)

    private fun addText(html: String, blocks: MutableList<BlogContentBlock>) {
        if (html.isBlank()) return
        val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00A0', ' ')
            .trim('\n', '\r')
        if (text.isNotBlank()) blocks += BlogContentBlock.Text(text)
    }

    private fun officialUrl(value: String): String? {
        val decoded = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        return when {
            decoded.startsWith("https://") -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("/") -> "https://www.nogizaka46.com$decoded"
            else -> null
        }
    }
}
