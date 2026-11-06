package com.catmusic.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the CatMusic activity is visible. The desktop lyric window is
 * only wanted after the user leaves the app; keeping a tiny state flow avoids
 * coupling the playback service to an Activity instance.
 */
object AppVisibilityState {
    // Start from "background" so a service restored without an Activity (for
    // example from a headset button) can still show the desktop lyric window.
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun setForeground(value: Boolean) {
        _foreground.value = value
    }
}
