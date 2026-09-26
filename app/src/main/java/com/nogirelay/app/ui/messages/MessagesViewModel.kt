package com.nogirelay.app.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.RelayMessage
import kotlinx.coroutines.Job
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
    val messages: List<RelayMessage> = emptyList(),
    val threads: List<MemberThread> = emptyList(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val translationEnabled: Boolean = false,
    val userNickname: String = "",
)

class MessagesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(AppGraph.dispatchers.databaseRead) {
            val settings = AppGraph.settings.read()
            val messages = AppGraph.database.latest()
            val latestPerMember = AppGraph.database.latestMessagePerMember()
            val unreadCounts = AppGraph.database.unreadCountsByMember()
            val threads = latestPerMember
                .map { latestMsg ->
                    MemberThread(
                        id = latestMsg.memberKey,
                        name = latestMsg.memberName,
                        avatarUrl = latestMsg.memberAvatarUrl,
                        latest = latestMsg,
                        unreadCount = unreadCounts[latestMsg.memberKey] ?: 0,
                    )
                }
                .sortedByDescending { it.latest.sentAt }
            _uiState.update { current ->
                current.copy(
                    loading = false,
                    messages = messages,
                    threads = threads,
                    unreadCounts = unreadCounts,
                    translationEnabled = settings.translationEnabled,
                    userNickname = settings.userNickname,
                )
            }
        }
    }
}
