package com.nogirelay.app.data.repository

import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.DataInvalidationTracker
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.db.BlogDao
import com.nogirelay.app.data.db.BlogMemberDao
import com.nogirelay.app.performance.PerformanceDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.map

/** Observable blog data; screens subscribe instead of polling the DAOs. */
class BlogRepository(
    private val dao: BlogDao,
    private val memberDao: BlogMemberDao,
    private val invalidation: DataInvalidationTracker,
    private val dispatchers: PerformanceDispatchers,
) {
    val unreadCount: Flow<Int> =
        invalidation.observe(dispatchers.databaseRead, DataVersions::unread) { dao.countUnreadBlogs() }

    /** Authors with posts, for filters and pickers. */
    val members: Flow<List<BlogMember>> =
        invalidation.observe(dispatchers.databaseRead, DataVersions::blogStructure) { memberDao.blogMembers() }

    /** Advances when the list itself may differ (new posts, bulk edits); the screen reloads. */
    val listRevision: Flow<Long> = invalidation.versions.map { it.blogs }.distinctUntilChanged()

    /** Every blog row change, for the list to patch just the rows it shows. */
    val rowChanges: Flow<BlogRowChanges> = invalidation.versions
        .distinctUntilChangedBy(DataVersions::blogRows)
        .map(::BlogRowChanges)

    /** One post, re-read whenever its content or translation may have changed. */
    fun post(id: String): Flow<BlogPost?> =
        invalidation.observe(dispatchers.databaseRead, DataVersions::blogContent) { dao.findBlog(id) }
}

/**
 * Blog row changes as the list sees them: [version] advances on every row
 * change; [changedSince] names the rows touched after an earlier version, or
 * null when the list must reload (it changed structurally, or too many rows did).
 */
@Immutable
class BlogRowChanges internal constructor(private val versions: DataVersions) {
    val version: Long get() = versions.blogRows

    /** The list structure revision these changes belong to. */
    val listRevision: Long get() = versions.blogs

    fun changedSince(version: Long): Set<String>? = versions.blogIdsSince(version)
}
