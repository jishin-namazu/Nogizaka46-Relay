package com.nogirelay.app.blog

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.repository.BlogRowChanges
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.ui.TimeFilter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Blog tab: the open article, search, filters and the list pager.
 *
 * Navigation and filter state is snapshot state, so text fields stay in
 * step with every keystroke; data (members, translation switch, list
 * revision) arrives as flows from the repositories. All of it outlives tab
 * switches; only scroll positions stay with the composables.
 */
class BlogViewModel(application: Application) : AndroidViewModel(application) {
    var selectedBlogId by mutableStateOf<String?>(null)
        private set
    var selectedMemberIds by mutableStateOf<Set<String>?>(null)
        private set
    var oldestFirst by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
    var timeFilter by mutableStateOf(TimeFilter())
        private set

    /** List index to reveal once the list shows again (notification deep links). */
    var pendingScrollIndex by mutableStateOf<Int?>(null)

    internal val pager = BlogListPager(application, viewModelScope, BlogPrewarmer.cachedSnapshot)

    val members: StateFlow<List<BlogMember>> = AppGraph.blogRepository.members
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BlogPrewarmer.cachedMembers.orEmpty())

    val translationEnabled: StateFlow<Boolean> = AppGraph.settings.settings
        .map { it.translationEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppGraph.settings.settings.value.translationEnabled)

    /** Advances whenever the stored list may differ; the screen reloads the visible window. */
    val listRevision: StateFlow<Long> = AppGraph.blogRepository.listRevision
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppGraph.invalidation.versions.value.blogs)

    /** Row-level changes the list patches in place. */
    val rowChanges: StateFlow<BlogRowChanges> = AppGraph.blogRepository.rowChanges
        .stateIn(viewModelScope, SharingStarted.Eagerly, BlogRowChanges(AppGraph.invalidation.versions.value))

    internal val listQuery: BlogListQuery
        get() = BlogListQuery(selectedMemberIds, searchQuery, oldestFirst, timeFilter, translationEnabled.value)

    val isFilterActive: Boolean
        get() {
            val all = members.value
            val memberFilterActive = selectedMemberIds != null && (all.isEmpty() || selectedMemberIds?.size != all.size)
            return timeFilter.isActive || memberFilterActive || oldestFirst
        }

    fun openBlog(id: String) {
        selectedBlogId = id
        // The list state keeps the exact item and pixel offset.
        pendingScrollIndex = null
    }

    fun closeBlog() {
        selectedBlogId = null
    }

    fun applyFilter(memberIds: Set<String>, filter: TimeFilter, oldest: Boolean) {
        val allIds = members.value.mapTo(linkedSetOf(), BlogMember::id)
        selectedMemberIds = memberIds.takeUnless { it == allIds }
        timeFilter = filter
        oldestFirst = oldest
    }

    /** Drops selected members that no longer exist (directory refresh). */
    fun pruneMemberSelection(available: List<BlogMember>) {
        val selected = selectedMemberIds ?: return
        val availableIds = available.mapTo(mutableSetOf(), BlogMember::id)
        val updated = selected.intersect(availableIds)
        if (updated != selected) selectedMemberIds = updated.takeUnless { it.size == availableIds.size }
    }

    /** Opens a post from a notification on a clean, default list. */
    fun openFromNotification(id: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            searchQuery = ""
            selectedMemberIds = null
            oldestFirst = false
            timeFilter = TimeFilter()
            pendingScrollIndex = withContext(AppGraph.dispatchers.databaseRead) { AppGraph.blogs.blogRank(id) }
            selectedBlogId = id
            onOpened()
        }
    }

    fun retranslate(id: String) {
        viewModelScope.launch {
            withContext(AppGraph.dispatchers.databaseWrite) { AppGraph.blogs.markBlogForRetranslation(id) }
            BlogTranslationManager.enqueue(getApplication(), id, force = true)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
