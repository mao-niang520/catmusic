package com.catmusic.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import com.catmusic.app.data.AdaptiveMotion
import com.catmusic.app.data.LyricFrameRate

/** Current refresh-rate policy, used by loops that choose their own cadence. */
val LocalMotionFrameRate = staticCompositionLocalOf { LyricFrameRate.ADAPTIVE }

/**
 * A display-clock-aligned time source throttled to the adaptive motion budget.
 *
 * Consumers read this state inside Canvas / graphicsLayer lambdas, so a frame
 * budget change invalidates drawing without recomposing the surrounding screen.
 */
val LocalAdaptiveMotionNanos = staticCompositionLocalOf<State<Long>> {
    mutableLongStateOf(0L)
}

/** 1f = idle; a menu-open ripple animates from 0f back to 1f. */
val LocalSceneRipple = staticCompositionLocalOf<State<Float>> {
    mutableFloatStateOf(1f)
}

@Composable
fun rememberAdaptiveMotionNanos(
    rate: LyricFrameRate,
    isPlaying: Boolean
): State<Long> {
    val frameNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(rate, isPlaying) {
        var nextFrameAt = 0L
        while (true) {
            withFrameNanos { now ->
                val interval = AdaptiveMotion.frameIntervalNanos(rate, isPlaying)
                if (interval == 0L) {
                    nextFrameAt = 0L
                    frameNanos.longValue = now
                } else {
                    if (nextFrameAt == 0L) nextFrameAt = now + interval
                    // The adaptive boost can tighten the budget while a stale
                    // deadline is still far away; pull it back in that case.
                    if (nextFrameAt - now > interval * 2) nextFrameAt = now + interval
                    if (now >= nextFrameAt) {
                        frameNanos.longValue = now
                        nextFrameAt += interval
                        if (nextFrameAt <= now) nextFrameAt = now + interval
                    }
                }
            }
        }
    }
    return frameNanos
}
