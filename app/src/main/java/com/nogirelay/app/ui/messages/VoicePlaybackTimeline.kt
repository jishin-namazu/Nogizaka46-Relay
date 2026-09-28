package com.nogirelay.app.ui.messages

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.ui.RelayPlaybackSlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Keep frame updates inside the timeline, rather than recomposing the card or member list. */
@Composable
internal fun VoicePlaybackTimeline(
    message: RelayMessage,
    audioState: VoicePlaybackState?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val playing = audioState?.isPlaying == true
    val animateProgress = enabled && !com.nogirelay.app.performance.LocalRelayPageWorkPaused.current
    val duration = (audioState?.durationMs?.takeIf { it > 0 }
        ?: message.durationSeconds?.takeIf { it > 0 }?.times(1_000)
        ?: 0).coerceAtLeast(0)
    val reportedPosition = audioState?.positionMs?.coerceIn(0, duration) ?: 0
    val latestState by rememberUpdatedState(audioState)
    var framePosition by remember(message.id) {
        mutableFloatStateOf(audioState?.positionAt(SystemClock.elapsedRealtime(), duration) ?: 0f)
    }
    var scrubbing by remember(message.id) { mutableStateOf(false) }
    var scrubPosition by remember(message.id) { mutableFloatStateOf(0f) }
    var pendingSeek by remember(message.id) { mutableStateOf<Int?>(null) }

    LaunchedEffect(message.id, playing, duration, scrubbing, pendingSeek, animateProgress) {
        if (!animateProgress || !playing || duration <= 0 || scrubbing || pendingSeek != null) return@LaunchedEffect
        framePosition = latestState?.positionAt(SystemClock.elapsedRealtime(), duration) ?: 0f
        while (isActive) {
            withFrameMillis {
                framePosition = latestState?.positionAt(SystemClock.elapsedRealtime(), duration) ?: 0f
            }
        }
    }
    LaunchedEffect(audioState, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (audioState != null && abs(audioState.positionMs.toLong() - target) <= 350L) {
            pendingSeek = null
        }
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            // Keep the thumb at the requested position until the service reports the seek.
            delay(800)
            pendingSeek = null
        }
    }

    val pendingPosition = pendingSeek
    val displayedPosition = when {
        scrubbing -> scrubPosition
        pendingPosition != null -> pendingPosition.toFloat()
        playing -> framePosition
        else -> reportedPosition.toFloat()
    }.coerceIn(0f, duration.toFloat())

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                formatAudioTime(displayedPosition.toInt()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatAudioDuration(duration),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RelayPlaybackSlider(
            value = displayedPosition,
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            onValueChange = { value ->
                pendingSeek = null
                scrubbing = true
                scrubPosition = value
            },
            onValueChangeFinished = {
                if (scrubbing) {
                    val target = scrubPosition.toInt().coerceIn(0, duration)
                    pendingSeek = target
                    framePosition = target.toFloat()
                    VoicePlaybackService.seek(context, message.id, target)
                    scrubbing = false
                }
            },
            enabled = enabled && duration > 0,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
