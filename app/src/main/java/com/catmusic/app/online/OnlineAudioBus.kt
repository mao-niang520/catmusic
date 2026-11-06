package com.catmusic.app.online

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Small in-process signal between the local Media3 player and the bundled
 * online page. The online preview is intentionally allowed to keep playing
 * while the user browses other tabs; when local playback starts, the page is
 * asked to pause so two audio streams never overlap.
 */
object OnlineAudioBus {

    private val _pauseRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pauseRequests = _pauseRequests.asSharedFlow()

    fun requestPause() {
        _pauseRequests.tryEmit(Unit)
    }
}
