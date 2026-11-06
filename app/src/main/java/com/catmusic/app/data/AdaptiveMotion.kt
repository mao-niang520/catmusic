package com.catmusic.app.data

import android.os.SystemClock

/**
 * Shared frame budget for long-running ambient motion.
 *
 * Adaptive mode is demand-driven: a still screen runs at a low cadence, normal
 * playback gets a modest step up, and gestures / track changes / lyric line
 * changes briefly unlock 60 fps. Scrolling and one-shot transitions are left to
 * the platform so normal UI interaction stays smooth.
 */
object AdaptiveMotion {
    private const val PULSE_MIN_INTERVAL_MS = 48L
    private const val BOOST_WINDOW_MS = 1_100L

    @Volatile
    private var lastPulseAt = 0L

    @Volatile
    private var lastMotionAt = 0L

    /** Marks a moment that deserves smoother frames. Calls are coalesced. */
    fun pulse(nowMs: Long = SystemClock.uptimeMillis()) {
        if (nowMs - lastPulseAt < PULSE_MIN_INTERVAL_MS) return
        lastPulseAt = nowMs
        lastMotionAt = nowMs
    }

    fun isBoosted(nowMs: Long = SystemClock.uptimeMillis()): Boolean =
        lastMotionAt != 0L && nowMs - lastMotionAt <= BOOST_WINDOW_MS

    /**
     * 0 means "no cap" (follow the display). Fixed modes stay fixed; only the
     * adaptive mode changes with playback and recent interaction.
     */
    fun targetFps(
        rate: LyricFrameRate,
        isPlaying: Boolean,
        nowMs: Long = SystemClock.uptimeMillis()
    ): Int = when (rate) {
        LyricFrameRate.ADAPTIVE -> when {
            isBoosted(nowMs) -> 60
            isPlaying -> 30
            else -> 24
        }

        LyricFrameRate.UNLIMITED -> 0
        else -> rate.fps
    }

    fun frameIntervalNanos(rate: LyricFrameRate, isPlaying: Boolean): Long {
        val fps = targetFps(rate, isPlaying)
        return if (fps <= 0) 0L else 1_000_000_000L / fps
    }

    /** Delay for coroutine-based loops that do not need vsync alignment. */
    fun frameDelayMillis(rate: LyricFrameRate, isPlaying: Boolean): Long {
        val fps = targetFps(rate, isPlaying)
        return when {
            fps <= 0 -> 6L
            else -> (1_000L / fps).coerceAtLeast(4L)
        }
    }
}
