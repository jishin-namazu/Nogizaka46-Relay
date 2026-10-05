package com.nogirelay.app.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.sync.SyncStatus
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.settings.SettingsPage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab(val label: String) { HOME("主页"), MESSAGES("消息"), BLOG("博客") }

/** Where the shell is: the visible tab and whether settings cover it. */
data class RelayShellState(
    val tab: AppTab = AppTab.HOME,
    val settingsOpen: Boolean = false,
    val settingsStartPage: SettingsPage? = null,
)

/**
 * State of the app shell. Like every screen ViewModel here, it holds the
 * navigation state and data subscriptions; composables keep only transient
 * visual state (animations, scroll, focus).
 */
class RelayAppViewModel(application: Application) : AndroidViewModel(application) {
    private val _shell = MutableStateFlow(RelayShellState())
    val shell: StateFlow<RelayShellState> = _shell.asStateFlow()

    val unreadMessages: StateFlow<Int> = AppGraph.messageRepository.unreadCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    val unreadBlogs: StateFlow<Int> = AppGraph.blogRepository.unreadCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    val sync: StateFlow<SyncStatus> = AppGraph.sync.status

    private val _reselected = MutableSharedFlow<AppTab>(extraBufferCapacity = 1)

    /** A tap on the tab that is already showing. */
    val reselected: SharedFlow<AppTab> = _reselected.asSharedFlow()

    init {
        // Changed credentials, model or scope resume translation work.
        viewModelScope.launch {
            AppGraph.settings.settings
                .map { listOf(it.translationEnabled, it.aiProvider, it.aiApiKey, it.aiModel, it.messageFullTranslation, it.blogFullTranslation) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    TranslationManager.resumeAfterPause(application)
                    BlogTranslationManager.resumeAfterPause(application)
                }
        }
    }

    fun selectTab(tab: AppTab) = _shell.update { it.copy(tab = tab) }

    fun reselectTab(tab: AppTab) {
        _reselected.tryEmit(tab)
    }

    /** Back from a non-home tab returns home. */
    fun backToHome(): Boolean {
        if (_shell.value.tab == AppTab.HOME) return false
        selectTab(AppTab.HOME)
        return true
    }

    fun openSettings(page: SettingsPage?) = _shell.update { it.copy(settingsOpen = true, settingsStartPage = page) }

    fun closeSettings() = _shell.update { it.copy(settingsOpen = false) }

    /** A notification opens its tab in front of everything, settings included. */
    fun showFromNotification(tab: AppTab) = _shell.update { it.copy(tab = tab, settingsOpen = false) }

    fun requestSync() = AppGraph.sync.request()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
