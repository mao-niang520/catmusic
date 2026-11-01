package com.catmusic.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared sleep-timer state between PlaybackService and the Compose UI.
 *
 * The countdown runs inside the foreground MediaSessionService, so it keeps
 * working with the screen off or the activity in the background. Values are
 * process-local: a timer is intentionally not restored after the app process
 * dies, because audio is no longer playing either.
 */
object SleepTimerState {

    const val ACTION_SET_SLEEP_TIMER = "com.catmusic.app.SLEEP_TIMER_SET"
    const val ACTION_CLEAR_SLEEP_TIMER = "com.catmusic.app.SLEEP_TIMER_CLEAR"
    const val ACTION_FADE_TOGGLE = "com.catmusic.app.FADE_TOGGLE"
    const val ARG_SLEEP_MINUTES = "sleep_minutes"

    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private val _totalMs = MutableStateFlow(0L)
    val totalMs: StateFlow<Long> = _totalMs.asStateFlow()

    fun arm(totalMs: Long) {
        _totalMs.value = totalMs.coerceAtLeast(0L)
        _remainingMs.value = totalMs.coerceAtLeast(0L)
    }

    fun update(remainingMs: Long) {
        _remainingMs.value = remainingMs.coerceAtLeast(0L)
    }

    fun clear() {
        _remainingMs.value = 0L
        _totalMs.value = 0L
    }
}
