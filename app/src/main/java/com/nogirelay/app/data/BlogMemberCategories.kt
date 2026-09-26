package com.nogirelay.app.data

/**
 * 归档 manifest 是成员分类 / 卒業标记的权威来源，但分类字符串必须落到 App 固定的期别清单
 * （[com.nogirelay.app.ui.transfer.MemberCategoryOrder]）里：BLOG 筛选是按分类字符串**全等**
 * 分组的，只要写法差一点就会单独多出一个分区。
 *
 * 实际遇到过两类非标准写法：
 *  1. `40006`「研究生」—— 2013〜2015 年 2期生 升格前共用的期别集体帐号，折回 2期生；
 *  2. 其余期别集体帐号 `40001` 新4期生 / `40003` 運営スタッフ / `40004` ３期生 /
 *     `40005` ４期生 / `40007` 5期生 / `40008` 6期生。官方接口给的是全角「３期生」「４期生」
 *     和「新4期生」，它们与名册里的「3期生」「4期生」不相等，于是各自变成一个分区。
 *
 * `insertMemberIfAbsent` 用的是 CONFLICT_IGNORE —— 已经导入过的行不会被新归档覆盖分类，所以
 * 读取（[MessageDatabase.blogMembers]）和导入（[com.nogirelay.app.data.transfer.DataImporter]）
 * 都必须在这里折一次，老库才能自己修好。
 *
 * `40006` 是官方 BLOG 的期别集体帐号（和 40001 / 40003 同号段），2013〜2015 年由 2期生
 * 在升格前共用，467 篇文章全部出自 2期生。
 */
object BlogMemberCategories {
    /** 官方 BLOG 里「研究生」帐号的 id。 */
    const val KENKYUSEI_ID = "40006"

    /** 该帐号在归档 manifest 里的名字。 */
    const val KENKYUSEI_NAME = "研究生"

    /** 「研究生」兼容层折回后的期别分类。 */
    const val KENKYUSEI_CATEGORY = "2期生"

    /**
     * 筛选分区的标准期别与顺序，UI 侧的 `MemberCategoryOrder` 直接以它为前缀，
     * 避免两处清单各自漂移。
     */
    val STANDARD_CATEGORIES = listOf(
        "6期生",
        "5期生",
        "4期生",
        "3期生",
        "2期生",
        "1期生",
        "運営スタッフ",
    )

    /** id 或名字命中「研究生」帐号（名字按全等匹配，避免误伤普通成员名）。 */
    fun isKenkyuseiAccount(id: String?, name: String?): Boolean {
        val trimmedId = id?.trim().orEmpty()
        val trimmedName = name?.trim().orEmpty()
        return trimmedId == KENKYUSEI_ID || trimmedName == KENKYUSEI_NAME
    }

    /**
     * 把一个分类字符串折回标准期别：先认全等，再按期别关键字（含全角、`新4期生`）归并。
     * 认不出来时返回 null，调用方决定要不要保留原值。
     */
    fun findStandardCategory(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        STANDARD_CATEGORIES.firstOrNull { it == trimmed }?.let { return it }
        return when {
            trimmed.contains("6期") || trimmed.contains("６期") -> "6期生"
            trimmed.contains("5期") || trimmed.contains("５期") -> "5期生"
            // `新4期生` 要先于 `4期` 判断，否则会被后一条吞掉。
            trimmed.contains("新4期") || trimmed.contains("新４期") -> "4期生"
            trimmed.contains("4期") || trimmed.contains("４期") -> "4期生"
            trimmed.contains("3期") || trimmed.contains("３期") -> "3期生"
            trimmed.contains("2期") || trimmed.contains("２期") -> "2期生"
            trimmed.contains("1期") || trimmed.contains("１期") -> "1期生"
            trimmed.contains("運営") || trimmed.contains("スタッフ") -> "運営スタッフ"
            else -> null
        }
    }

    /**
     * 归档 / 官方接口 / 已落库的行都可能带非标准分类，统一折回标准期别。
     * 分类缺失或认不出来时退回用名字判断；两者都认不出就保留原值，全空时给「其他」。
     */
    fun normalizeCategory(id: String?, name: String?, category: String?): String {
        val trimmedCategory = category?.trim().orEmpty()
        if (isKenkyuseiAccount(id, name) || trimmedCategory == KENKYUSEI_NAME) {
            return KENKYUSEI_CATEGORY
        }
        findStandardCategory(trimmedCategory)?.let { return it }
        findStandardCategory(name)?.let { return it }
        return trimmedCategory.ifEmpty { "其他" }
    }
}
