package com.nogirelay.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextTest {

    @Test
    fun testHighlightMatchesBasic() {
        val text = "乃木坂46の山下美月です"
        val query = "山下美月"
        val result = highlightMatches(text, query, textColor = Color.Red)
        assertEquals(text, result.text)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles[0]
        assertEquals(6, span.start)
        assertEquals(10, span.end)
        assertEquals(Color.Red, span.item.color)
        // Ensure background is NOT set to prevent Android's asymmetric line-height box
        assertEquals(Color.Unspecified, span.item.background)
    }

    @Test
    fun testHighlightMatchesCaseInsensitive() {
        val text = "Hello Nogi World"
        val query = "nogi"
        val result = highlightMatches(text, query, backgroundColor = Color.Yellow, textColor = Color.Blue)
        assertEquals(text, result.text)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles[0]
        assertEquals(6, span.start)
        assertEquals(10, span.end)
        assertEquals(Color.Blue, span.item.color)
        assertEquals(Color.Unspecified, span.item.background)
    }

    @Test
    fun testHighlightMatchesEmptyQuery() {
        val text = "乃木坂46"
        val query = "   "
        val result = highlightMatches(text, query, textColor = Color.Red)
        assertEquals(text, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun testSearchSnippet() {
        val text = "今日は天気がいいですね。乃木坂46のライブがあります！楽しみです。"
        val snippet = searchSnippet(text, "ライブ")
        assertTrue(snippet != null && snippet.contains("ライブ"))
    }

    @Test
    fun testSearchSnippetsMultipleDisjoint() {
        val text = "第1段落でライブが開催されました。その間にはとても長い文章が入ります。第2段落でもライブの感想を述べています。"
        val snippets = searchSnippets(text, "ライブ")
        assertEquals(2, snippets.size)
        assertTrue(snippets[0].contains("第1段落"))
        assertTrue(snippets[0].contains("ライブ"))
        assertTrue(snippets[1].contains("第2段落"))
        assertTrue(snippets[1].contains("ライブ"))
    }

    @Test
    fun testSearchSnippetsOverlappingMerged() {
        val text = "ライブとライブが連続する場合のテストです。"
        val snippets = searchSnippets(text, "ライブ")
        // Overlapping/adjacent occurrences should merge into 1 snippet
        assertEquals(1, snippets.size)
        assertTrue(snippets[0].contains("ライブとライブ"))
    }

    @Test
    fun testSearchSnippetsMaxLimit() {
        val text = "AライブB 12345678901234567890 CライブD 12345678901234567890 EライブF"
        val snippets = searchSnippets(text, "ライブ", maxSnippets = 2)
        assertEquals(2, snippets.size)
    }

    @Test
    fun testSearchSnippetsNoMatchOrEmpty() {
        assertEquals(0, searchSnippets("乃木坂46", "櫻坂").size)
        assertEquals(0, searchSnippets("", "乃木坂").size)
        assertEquals(0, searchSnippets("乃木坂46", "   ").size)
    }
}
