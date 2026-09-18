package com.nogirelay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档 manifest 是分类的权威来源，但它可能带着非标准写法（老归档的「研究生」、官方接口的全角
 * 「３期生」「４期生」、期别集体帐号原名「新4期生」）。BLOG 筛选按分类字符串全等分组，差一个字就
 * 会多出一个分区，所以读取 / 导入都要在这里折回标准期别。
 */
class BlogMemberCategoriesTest {

    @Test
    fun kenkyuseiAccountIsRecognisedByIdOrName() {
        assertTrue(BlogMemberCategories.isKenkyuseiAccount("40006", null))
        assertTrue(BlogMemberCategories.isKenkyuseiAccount(null, "研究生"))
        assertTrue(BlogMemberCategories.isKenkyuseiAccount(" ", " 研究生 "))
        assertFalse(BlogMemberCategories.isKenkyuseiAccount("40005", "４期生"))
        assertFalse(BlogMemberCategories.isKenkyuseiAccount("55387", "弓木 奈於"))
    }

    @Test
    fun nameMatchIsExactSoRealMembersAreNotSwallowed() {
        assertFalse(BlogMemberCategories.isKenkyuseiAccount(null, "研究生A"))
        assertFalse(BlogMemberCategories.isKenkyuseiAccount(null, "大学院研究生"))
    }

    @Test
    fun legacyKenkyuseiCategoryFoldsIntoSecondGeneration() {
        assertEquals("2期生", BlogMemberCategories.normalizeCategory("40006", "研究生", "研究生"))
        assertEquals("2期生", BlogMemberCategories.normalizeCategory("40006", "研究生", ""))
        assertEquals("2期生", BlogMemberCategories.normalizeCategory(null, "研究生", "研究生"))
    }

    @Test
    fun otherMembersKeepTheirOwnCategory() {
        assertEquals("4期生", BlogMemberCategories.normalizeCategory("55387", "弓木 奈於", "4期生"))
        assertEquals("運営スタッフ", BlogMemberCategories.normalizeCategory("40003", "運営スタッフ", "運営スタッフ"))
        // 分类缺失且名字认不出期别时归入「其他」，和以前 deduceBlogCategory 的兜底一致。
        assertEquals("其他", BlogMemberCategories.normalizeCategory("55387", "弓木 奈於", null))
        assertEquals("其他", BlogMemberCategories.normalizeCategory("55387", "弓木 奈於", ""))
    }

    /**
     * 期别集体帐号：官方接口给的是全角「３期生」「４期生」和「新4期生」，和名册里的
     * 「3期生」「4期生」不相等；不折回就会在筛选里多出三个分区。
     */
    @Test
    fun collectiveAccountLabelsFoldIntoStandardPeriods() {
        assertEquals("3期生", BlogMemberCategories.normalizeCategory("40004", "３期生", "３期生"))
        assertEquals("4期生", BlogMemberCategories.normalizeCategory("40005", "４期生", "４期生"))
        assertEquals("4期生", BlogMemberCategories.normalizeCategory("40001", "新4期生", "新4期生"))
        assertEquals("5期生", BlogMemberCategories.normalizeCategory("40007", "5期生", "5期生"))
        assertEquals("6期生", BlogMemberCategories.normalizeCategory("40008", "6期生", "6期生"))
        assertEquals("運営スタッフ", BlogMemberCategories.normalizeCategory("40003", "運営スタッフ", "運営スタッフ"))
    }

    @Test
    fun categoryCanBeRecoveredFromTheMemberName() {
        assertEquals("6期生", BlogMemberCategories.normalizeCategory("40008", "6期生", null))
        assertEquals("4期生", BlogMemberCategories.normalizeCategory("40005", "４期生", ""))
    }

    @Test
    fun unknownCategoryIsLeftAlone() {
        assertEquals("研修生", BlogMemberCategories.normalizeCategory("99999", "某人", "研修生"))
    }

    @Test
    fun standardCategoriesMatchTheFilterOrder() {
        assertEquals(7, BlogMemberCategories.STANDARD_CATEGORIES.size)
        assertTrue(BlogMemberCategories.findStandardCategory("6期生") == "6期生")
        assertTrue(BlogMemberCategories.findStandardCategory("新４期生") == "4期生")
    }
}
