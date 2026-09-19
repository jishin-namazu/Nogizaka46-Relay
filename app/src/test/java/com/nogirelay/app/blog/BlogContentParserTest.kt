package com.nogirelay.app.blog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [BlogContentParser.bodyTextShape] 决定一次同步是否该作废已存译文：只有文字或图片结构
 * 真的变了才作废，单纯换图片主机 / 相对⇄绝对地址不算。
 */
class BlogContentParserTest {

    @Test
    fun mirrorToOfficialHostKeepsShape() {
        val before = """<div>こんにちは<img src="https://images.sakamichi.co/blogs/01JHFWPB7ZK2RFY60QMRTCMHAF"></div>"""
        val after = """<div>こんにちは<img src="https://www.nogizaka46.com/files/46/diary/n46/MEMBER/moblog/202303/mobVU2GGk.jpg"></div>"""
        assertEquals(BlogContentParser.bodyTextShape(before), BlogContentParser.bodyTextShape(after))
    }

    @Test
    fun relativeToAbsoluteKeepsShape() {
        val before = """<div><img src="/files/46/diary/n46/MEMBER/moblog/202609/mob4ASchQ.jpg">本文</div>"""
        val after = """<div><img src="https://www.nogizaka46.com/files/46/diary/n46/MEMBER/moblog/202609/mob4ASchQ.jpg">本文</div>"""
        assertEquals(BlogContentParser.bodyTextShape(before), BlogContentParser.bodyTextShape(after))
    }

    @Test
    fun attributeOnlyDifferenceKeepsShape() {
        val before = """<img src="a.jpg" style="width: 10px">本文"""
        val after = """<img src="b.jpg" loading="lazy">本文"""
        assertEquals(BlogContentParser.bodyTextShape(before), BlogContentParser.bodyTextShape(after))
    }

    @Test
    fun textChangeAltersShape() {
        val before = """<div>原文<img src="a.jpg"></div>"""
        val after = """<div>改过的原文<img src="a.jpg"></div>"""
        assertNotEquals(BlogContentParser.bodyTextShape(before), BlogContentParser.bodyTextShape(after))
    }

    @Test
    fun addedOrRemovedImageAltersShape() {
        val before = """<div>本文<img src="a.jpg"></div>"""
        val after = """<div>本文<img src="a.jpg"><img src="b.jpg"></div>"""
        assertNotEquals(BlogContentParser.bodyTextShape(before), BlogContentParser.bodyTextShape(after))
        assertNotEquals(
            BlogContentParser.bodyTextShape(before),
            BlogContentParser.bodyTextShape("""<div>本文</div>"""),
        )
    }
}
