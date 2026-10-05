package com.nogirelay.app.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.repository.MemberSummary
import com.nogirelay.app.data.repository.MessageChanges
import com.nogirelay.app.media.VoicePlaybackService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    val translationEnabled: Boolean = false,
    val userNickname: String = "",
    /** Whether a sync server is set up; the empty inbox points to setup when not. */
    val relayConfigured: Boolean = true,
)

/**
 * The Messages tab: member threads, the open conversation and the change log
 * its timeline follows. Data arrives by subscription and only while the tab
 * is active ([setActive]); the open conversation survives tab switches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel : ViewModel() {
    private val active = MutableStateFlow(false)

    private val threads = active.flatMapLatest { isActive ->
        if (isActive) AppGraph.messageRepository.memberSummaries.map { it.map(MemberSummary::toThread) } else emptyFlow()
    }

    val uiState: StateFlow<MessagesUiState> = combine(threads, AppGraph.settings.settings) { threads, settings ->
        MessagesUiState(
            loading = false,
            threads = threads.sortedByDescending { it.latest.sentAt },
            translationEnabled = settings.translationEnabled,
            userNickname = settings.userNickname,
            relayConfigured = settings.relayUrl.isNotBlank() || com.nogirelay.app.data.api.ApiConfig.BASE_URL.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MessagesUiState())

    /** What changed in the message table, for the open timeline to patch itself. */
    val messageChanges: StateFlow<MessageChanges> = AppGraph.messageRepository.changes
        .stateIn(viewModelScope, SharingStarted.Eagerly, MessageChanges(AppGraph.invalidation.versions.value))

    private val _openEntry = MutableStateFlow<MemberMessageEntry?>(null)

    /** The member conversation on screen, if any. */
    internal val openEntry: StateFlow<MemberMessageEntry?> = _openEntry.asStateFlow()

    fun setActive(isActive: Boolean) {
        active.value = isActive
    }

    internal fun openMember(memberKey: String, notificationMessageId: String? = null) {
        _openEntry.value = MemberMessageEntry(
            memberKey = memberKey,
            playbackState = VoicePlaybackService.playbackState.value,
            notificationMessageId = notificationMessageId,
        )
    }

    /** Reopens [entry] (a collapse turned back into an expansion). */
    internal fun reopen(entry: MemberMessageEntry) {
        _openEntry.value = entry
    }

    internal fun close(entry: MemberMessageEntry? = null) {
        if (entry == null || _openEntry.value === entry) _openEntry.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun MemberSummary.toThread() = MemberThread(
    id = latest.memberKey,
    name = latest.memberName,
    avatarUrl = latest.memberAvatarUrl,
    latest = latest,
    unreadCount = unreadCount,
)
