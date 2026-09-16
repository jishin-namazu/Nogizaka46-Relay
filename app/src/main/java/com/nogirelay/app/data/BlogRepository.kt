package com.nogirelay.app.data

import com.nogirelay.app.blog.BlogContentParser
import com.nogirelay.app.ui.searchSnippet
import kotlinx.coroutines.withContext

data class BlogPageRequest(
    val memberIds: Set<String>? = null,
    val searchQuery: String = "",
    val oldestFirst: Boolean = false,
    val startMillis: Long? = null,
    val endMillisExclusive: Long? = null,
    val page: Int = 0,
    val pageSize: Int = 20,
)

data class BlogSearchPreview(val label: String, val text: String)

data class BlogPageResult(
    val members: List<BlogMember>,
    val posts: List<BlogSummary>,
    val previews: Map<String, List<BlogSearchPreview>>,
    val currentPage: Int,
    val totalPages: Int,
    val totalCount: Int,
    val matchingCount: Int,
    val translationEnabled: Boolean,
)

class BlogRepository(
    private val database: MessageDatabase,
    private val settings: SettingsStore,
    private val dispatchers: com.nogirelay.app.performance.PerformanceDispatchers,
) {
    suspend fun loadPage(request: BlogPageRequest): BlogPageResult =
        withContext(dispatchers.databaseRead) {
            val translationEnabled = settings.read().translationEnabled
            val members = database.blogMembers()
            val total = database.countBlogs()
            val matching = database.countBlogs(
                memberIds = request.memberIds,
                searchQuery = request.searchQuery,
                startMillis = request.startMillis,
                endMillisExclusive = request.endMillisExclusive,
            )
            val totalPages = ((matching + request.pageSize - 1) / request.pageSize).coerceAtLeast(1)
            val page = request.page.coerceIn(0, totalPages - 1)
            val posts = database.blogSummaries(
                memberIds = request.memberIds,
                searchQuery = request.searchQuery,
                oldestFirst = request.oldestFirst,
                startMillis = request.startMillis,
                endMillisExclusive = request.endMillisExclusive,
                limit = request.pageSize,
                offset = page * request.pageSize,
            )
            val previews = if (request.searchQuery.isBlank() || posts.isEmpty()) {
                emptyMap()
            } else {
                searchPreviews(posts, request.searchQuery, translationEnabled)
            }
            BlogPageResult(
                members = members,
                posts = posts,
                previews = previews,
                currentPage = page,
                totalPages = totalPages,
                totalCount = total,
                matchingCount = matching,
                translationEnabled = translationEnabled,
            )
        }

    private fun searchPreviews(
        posts: List<BlogSummary>,
        query: String,
        translationEnabled: Boolean,
    ): Map<String, List<BlogSearchPreview>> = database.blogSearchSources(posts.map(BlogSummary::id))
        .mapNotNull { source ->
            val excerpts = buildList {
                searchSnippet(
                    BlogContentParser.plainText(BlogContentParser.blocks(source.bodyHtml)),
                    query,
                )?.let { add(BlogSearchPreview("原文", it)) }
                if (translationEnabled) {
                    searchSnippet(translationText(source.translation), query)
                        ?.let { add(BlogSearchPreview("译文", it)) }
                }
            }
            excerpts.takeIf { it.isNotEmpty() }?.let { source.id to it }
        }
        .toMap()

    private fun translationText(serialized: String?): String {
        if (serialized.isNullOrBlank()) return ""
        return runCatching {
            val array = org.json.JSONArray(serialized)
            (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
                .joinToString("\n")
        }.getOrDefault("")
    }
}
