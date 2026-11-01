package com.catmusic.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

@Stable
class PlayerUiSnapshot {
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var currentMediaId by mutableStateOf<String?>(null)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var hasNext by mutableStateOf(false)
        private set
    var hasPrevious by mutableStateOf(false)
        private set
    var hasCurrent by mutableStateOf(false)
        private set

    fun updateFrom(player: Player) {
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        currentMediaId = player.currentMediaItem?.mediaId
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it > 0L } ?: 0L
        shuffleEnabled = player.shuffleModeEnabled
        repeatMode = player.repeatMode
        hasNext = player.hasNextMediaItem()
        hasPrevious = player.hasPreviousMediaItem()
        hasCurrent = player.currentMediaItem != null
    }

    fun updateProgress(player: Player) {
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it > 0L } ?: durationMs
        hasNext = player.hasNextMediaItem()
        hasPrevious = player.hasPreviousMediaItem()
    }

    fun reset() {
        isPlaying = false
        isBuffering = false
        currentMediaId = null
        positionMs = 0L
        durationMs = 0L
        shuffleEnabled = false
        repeatMode = Player.REPEAT_MODE_OFF
        hasNext = false
        hasPrevious = false
        hasCurrent = false
    }
}

@Composable
fun rememberPlayerUiSnapshot(controller: MediaController?): PlayerUiSnapshot {
    val snapshot = remember { PlayerUiSnapshot() }

    DisposableEffect(controller) {
        if (controller == null) {
            snapshot.reset()
            return@DisposableEffect onDispose { }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshot.updateFrom(player)
            }
        }
        controller.addListener(listener)
        snapshot.updateFrom(controller)
        onDispose {
            controller.removeListener(listener)
        }
    }

    LaunchedEffect(controller, snapshot.isPlaying, snapshot.currentMediaId) {
        while (controller != null && snapshot.isPlaying) {
            snapshot.updateProgress(controller)
            delay(250L)
        }
    }

    return snapshot
}
