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

/** Raw text sources used to build a BLOG search summary without loading whole rows. */
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
)

data class BlogPage(
    val total: Int,
    val posts: List<BlogPost>,
)
