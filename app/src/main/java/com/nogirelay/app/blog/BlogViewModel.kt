package com.nogirelay.app.blog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogPageRequest
import com.nogirelay.app.data.BlogRepository
import com.nogirelay.app.data.BlogSearchPreview
import com.nogirelay.app.data.BlogSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlogUiState(
    val loading: Boolean = true,
    val members: List<BlogMember> = emptyList(),
    val posts: List<BlogSummary> = emptyList(),
    val previews: Map<String, List<BlogSearchPreview>> = emptyMap(),
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val totalCount: Int = 0,
    val matchingCount: Int = 0,
    val translationEnabled: Boolean = false,
    val error: String? = null,
)

class BlogViewModel(private val repository: BlogRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(BlogUiState())
    val uiState: StateFlow<BlogUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun loadPage(request: BlogPageRequest) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repository.loadPage(request) }
                .onSuccess { result ->
                    _uiState.value = BlogUiState(
                        loading = false,
                        members = result.members,
                        posts = result.posts,
                        previews = result.previews,
                        currentPage = result.currentPage,
                        totalPages = result.totalPages,
                        totalCount = result.totalCount,
                        matchingCount = result.matchingCount,
                        translationEnabled = result.translationEnabled,
                    )
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    _uiState.update { it.copy(loading = false, error = error.message) }
                }
        }
    }

    companion object {
        fun factory(repository: BlogRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BlogViewModel(repository) as T
            }
    }
}
