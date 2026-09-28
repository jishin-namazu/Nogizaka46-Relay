package com.nogirelay.app.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.RelayMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemberThread(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val latest: RelayMessage,
    val unreadCount: Int,
)

data class MessagesUiState(
    val loading: Boolean = true,
    val threads: List<MemberThread> = emptyList(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val translationEnabled: Boolean = false,
    val userNickname: String = "",
)

class MessagesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun load(versions: DataVersions) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(AppGraph.dispatchers.databaseRead) {
            val settings = AppGraph.settings.read()
            val summaries = AppGraph.memberSummaries.load(versions)
            val threads = summaries
                .map { (latestMsg, unreadCount) ->
                    MemberThread(
                        id = latestMsg.memberKey,
                        name = latestMsg.memberName,
                        avatarUrl = latestMsg.memberAvatarUrl,
                        latest = latestMsg,
                        unreadCount = unreadCount,
                    )
                }
                .sortedByDescending { it.latest.sentAt }
            coroutineContext.ensureActive()
            _uiState.update { current ->
                current.copy(
                    loading = false,
                    threads = threads,
                    unreadCounts = threads.associate { it.id to it.unreadCount },
                    translationEnabled = settings.translationEnabled,
                    userNickname = settings.userNickname,
                )
            }
        }
    }

    fun stopLoading() { loadJob?.cancel() }
}
