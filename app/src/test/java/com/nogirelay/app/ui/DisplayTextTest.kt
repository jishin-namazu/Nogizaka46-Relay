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
}
