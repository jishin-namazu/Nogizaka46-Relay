package com.nogirelay.app.ui.messages

import com.nogirelay.app.media.VoicePlaybackState

internal class MemberMessageEntry(
    val memberKey: String,
    playbackState: VoicePlaybackState,
    val notificationMessageId: String? = null,
) {
    val targetMessageId: String? = notificationMessageId
        ?: playbackState.messageId.takeIf { playbackState.isPlaying }
}
