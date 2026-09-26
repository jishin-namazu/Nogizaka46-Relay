package com.nogirelay.app.data

data class BlogPost(
    val id: String,
    val memberId: String,
    val memberName: String,
    val memberAvatarUrl: String?,
    val title: String,
    val bodyHtml: String,
    val imageUrl: String?,
    val publishedAt: String,
    val postUrl: String,
    val translation: String? = null,
    val translationDone: Boolean = false,
    val isUnread: Boolean = false,
)

data class BlogSummary(
    val id: String,
    val memberId: String,
    val memberName: String,
    val memberAvatarUrl: String?,
    val title: String,
    val imageUrl: String?,
    val publishedAt: String,
    val isUnread: Boolean,
    val translatedTitle: String? = null,
)

/** 用于在不加载整行数据的情况下构建 BLOG 搜索摘要的原始文本来源。 */
data class BlogSearchSource(
    val id: String,
    val bodyHtml: String,
    val translation: String?,
)

data class BlogMember(
    val id: String,
    val name: String,
    val category: String,
    val avatarUrl: String?,
    val displayOrder: Int,
    /** 官方名册仍会列出已毕业成员；这就是它的“毕业”标记。 */
    val graduated: Boolean = false,
)

data class BlogPage(
    val total: Int,
    val posts: List<BlogPost>,
)

fun isRealBlogImageUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val trimmed = url.trim()
    if (trimmed.endsWith("/none.png", ignoreCase = true) ||
        trimmed.contains("/blog/none.", ignoreCase = true) ||
        trimmed.contains("no_image", ignoreCase = true)
    ) {
        return false
    }
    return true
}
