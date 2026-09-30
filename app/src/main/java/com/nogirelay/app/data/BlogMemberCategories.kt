package com.nogirelay.app.data

object BlogMemberCategories {

    const val KENKYUSEI_ID = "40006"

    const val KENKYUSEI_NAME = "研究生"

    const val KENKYUSEI_CATEGORY = "2期生"

    val STANDARD_CATEGORIES = listOf(
        "6期生",
        "5期生",
        "4期生",
        "3期生",
        "2期生",
        "1期生",
        "運営スタッフ",
    )

    fun isKenkyuseiAccount(id: String?, name: String?): Boolean {
        val trimmedId = id?.trim().orEmpty()
        val trimmedName = name?.trim().orEmpty()
        return trimmedId == KENKYUSEI_ID || trimmedName == KENKYUSEI_NAME
    }

    fun findStandardCategory(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        STANDARD_CATEGORIES.firstOrNull { it == trimmed }?.let { return it }
        return when {
            trimmed.contains("6期") || trimmed.contains("６期") -> "6期生"
            trimmed.contains("5期") || trimmed.contains("５期") -> "5期生"

            trimmed.contains("新4期") || trimmed.contains("新４期") -> "4期生"
            trimmed.contains("4期") || trimmed.contains("４期") -> "4期生"
            trimmed.contains("3期") || trimmed.contains("３期") -> "3期生"
            trimmed.contains("2期") || trimmed.contains("２期") -> "2期生"
            trimmed.contains("1期") || trimmed.contains("１期") -> "1期生"
            trimmed.contains("運営") || trimmed.contains("スタッフ") -> "運営スタッフ"
            else -> null
        }
    }

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
