package com.nogirelay.app.ui.messages

import com.nogirelay.app.media.VoicePlaybackState

// Each visit has its own identity, including a quick back/reopen during the crossfade.
internal class MemberMessageEntry(
    val memberKey: String,
    playbackState: VoicePlaybackState,
    val notificationMessageId: String? = null,
) {
    val targetMessageId: String? = notificationMessageId
        ?: playbackState.messageId.takeIf { playbackState.isPlaying }
}
