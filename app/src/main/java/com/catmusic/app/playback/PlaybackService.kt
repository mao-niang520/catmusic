package com.catmusic.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.catmusic.app.MainActivity
import com.catmusic.app.R
import com.catmusic.app.data.LyricDisplayLoader
import com.catmusic.app.data.LyricTranslationMode
import com.catmusic.app.data.MusicRepository
import com.catmusic.app.data.Track
import com.catmusic.app.util.ParsedLyrics
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var repository: MusicRepository
    private lateinit var player: ExoPlayer
    private lateinit var audioReactiveTap: AudioReactivePcmTap
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statsJob: Job? = null
    private var fadeJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var sleepTimerGeneration = 0
    private var lastTickAt = 0L
    private var countedMediaId: String? = null
    private var desktopOverlay: DesktopLyricsOverlay? = null
    private var overlayLyrics: ParsedLyrics? = null
    private var overlayTrackKey: String? = null
    private var overlayTranslationJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                countPlayIfNeeded()
                startStatsTicker()
                startPlaybackFadeIn()
            } else {
                stopStatsTicker()
                fadeJob?.cancel()
                fadeJob = null
                if (player.volume < 1f) player.volume = 1f
                // The PCM sink stops receiving buffers while paused. Clear
                // the last snapshot so the WebView sound field does not keep the
                // final frame of audio energy frozen on screen.
                AudioReactiveSignal.reset()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId
            if (player.isPlaying) {
                if (!mediaId.isNullOrBlank()) {
                    repository.recordPlay(mediaId)
                    countedMediaId = mediaId
                }
            } else {
                // A different item selected while paused should count once playback starts.
                countedMediaId = null
            }
            updateKnownDuration()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                updateKnownDuration()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback failed: ${error.errorCodeName}", error)
            repository.flushStats()
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = MusicRepository.get(this)

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.notification_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_cat) }
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        audioReactiveTap = AudioReactivePcmTap {
            repository.audioReactiveEnabled.value || AudioReactiveSignal.uiRequested
        }
        player = ExoPlayer.Builder(
            this,
            AudioReactiveRenderersFactory(this, audioReactiveTap)
        )
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(listener)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setCustomLayout(listOf(desktopLyricsButton()))
            .build()

        serviceScope.launch {
            repository.desktopLyricsEnabled.collectLatest {
                mediaSession?.setCustomLayout(listOf(desktopLyricsButton()))
            }
        }

        desktopOverlay = DesktopLyricsOverlay(
            context = this,
            initialConfig = repository.desktopLyricsConfig.value,
            onConfigChanged = repository::setDesktopLyricsConfig,
            onCloseRequested = { repository.setDesktopLyricsEnabled(false) }
        )
        serviceScope.launch {
            repository.desktopLyricsConfig.collectLatest { config ->
                desktopOverlay?.applyConfig(config)
            }
        }
        startDesktopLyricsWatcher()
    }

    private fun desktopLyricsButton(): CommandButton {
        val enabled = repository.desktopLyricsEnabled.value
        return CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_desktop_lyrics)
            .setDisplayName(if (enabled) "关闭桌面歌词" else "开启桌面歌词")
            .setSessionCommand(
                SessionCommand(ACTION_TOGGLE_DESKTOP_LYRICS, Bundle.EMPTY)
            )
            .build()
    }

    /**
     * Entry point used by the media notification. The media controls are often
     * the fastest way to switch floating lyrics on while already in a game.
     */
    private fun toggleDesktopLyricsFromSession() {
        if (repository.desktopLyricsEnabled.value) {
            repository.setDesktopLyricsEnabled(false)
            Toast.makeText(this, "桌面歌词已关闭", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "请先允许 CatMusic 显示在其他应用上层，再点一次",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }
            return
        }
        repository.setDesktopLyricsEnabled(true)
        Toast.makeText(this, "桌面歌词已开启", Toast.LENGTH_SHORT).show()
    }

    private fun startDesktopLyricsWatcher() {
        serviceScope.launch {
            combine(
                repository.desktopLyricsEnabled,
                AppVisibilityState.foreground
            ) { enabled, foreground -> enabled && !foreground }
                .distinctUntilChanged()
                .collectLatest { visible ->
                    resetDesktopLyrics()
                    if (!visible || !Settings.canDrawOverlays(this@PlaybackService)) {
                        return@collectLatest
                    }
                    try {
                        while (isActive) {
                            runCatching { tickDesktopLyrics() }
                            delay(180L)
                        }
                    } finally {
                        resetDesktopLyrics()
                    }
                }
        }
    }

    private fun resetDesktopLyrics() {
        overlayTranslationJob?.cancel()
        overlayTranslationJob = null
        overlayLyrics = null
        overlayTrackKey = null
        desktopOverlay?.hide()
    }

    private suspend fun tickDesktopLyrics() {
        val mediaId = player.currentMediaItem?.mediaId
        val track = mediaId?.let { id ->
            repository.tracks.value.firstOrNull { it.id == id }
        }
        if (track == null || track.lyricsFile.isNullOrBlank()) {
            desktopOverlay?.hide()
            return
        }
        val mode = repository.lyricTranslationMode.value
        val key = "${track.id}|${track.lyricsFile}|${mode.key}"
        var parsed = overlayLyrics
        if (overlayTrackKey != key || parsed == null) {
            overlayTranslationJob?.cancel()
            overlayTranslationJob = null
            overlayLyrics = null
            overlayTrackKey = key
            val base = LyricDisplayLoader.load(this, track, mode)
            if (base == null || base.lines.isEmpty()) {
                desktopOverlay?.hide()
                return
            }
            overlayLyrics = base
            parsed = base
            if (mode == LyricTranslationMode.ONLINE) {
                overlayTranslationJob = serviceScope.launch {
                    val enriched = LyricDisplayLoader.enrich(this@PlaybackService, track, base)
                    if (overlayTrackKey == key) overlayLyrics = enriched
                }
            }
        }
        val current = parsed ?: return
        val position = (
            player.currentPosition - track.lyricOffsetMs
            ).coerceAtLeast(0L)
        val index = current.activeIndex(position)
        val line = current.lines.getOrNull(index)
        if (line == null) {
            desktopOverlay?.hide()
        } else {
            desktopOverlay?.show(line)
        }
    }

    /**
     * Custom commands let the Compose controller request a fade-aware toggle
     * and a sleep timer that keeps running inside the foreground service.
     */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            if (!base.isAccepted) return base
            val commands = base.availableSessionCommands.buildUpon()
                .add(SessionCommand(SleepTimerState.ACTION_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(SleepTimerState.ACTION_CLEAR_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(SleepTimerState.ACTION_FADE_TOGGLE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_DESKTOP_LYRICS, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                commands,
                base.availablePlayerCommands
            )
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            session.setCustomLayout(controller, listOf(desktopLyricsButton()))
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SleepTimerState.ACTION_SET_SLEEP_TIMER -> {
                    startSleepTimer(args.getInt(SleepTimerState.ARG_SLEEP_MINUTES, 0))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                SleepTimerState.ACTION_CLEAR_SLEEP_TIMER -> {
                    clearSleepTimer()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                SleepTimerState.ACTION_FADE_TOGGLE -> {
                    toggleWithFade()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                ACTION_TOGGLE_DESKTOP_LYRICS -> {
                    toggleDesktopLyricsFromSession()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private fun startPlaybackFadeIn() {
        fadeJob?.cancel()
        val durationMs = repository.fadeInMs.value.coerceAtLeast(0)
        if (durationMs == 0) {
            fadeJob = null
            player.volume = 1f
            return
        }
        player.volume = 0f
        fadeJob = serviceScope.launch {
            fadeVolumeTo(1f, durationMs.toLong())
        }
    }

    /**
     * Fade before pausing when the in-app button is used, and fade back in
     * when it is used to resume. Notification/headset controls still use the
     * native immediate pause; playback always fades in when it starts.
     */
    private fun toggleWithFade() {
        if (player.currentMediaItem == null) return
        if (player.isPlaying) {
            fadeJob?.cancel()
            val durationMs = repository.fadeOutMs.value.coerceAtLeast(0)
            if (durationMs == 0) {
                fadeJob = null
                player.pause()
                player.volume = 1f
            } else {
                fadeJob = serviceScope.launch {
                    fadeVolumeTo(0f, durationMs.toLong())
                    currentCoroutineContext().ensureActive()
                    player.pause()
                    player.volume = 1f
                }
            }
        } else {
            fadeJob?.cancel()
            when (player.playbackState) {
                Player.STATE_IDLE -> player.prepare()
                Player.STATE_ENDED -> player.seekToDefaultPosition()
            }
            player.volume = 0f
            player.play()
        }
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerGeneration++
        val generation = sleepTimerGeneration
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1f
        if (minutes <= 0) {
            SleepTimerState.clear()
            return
        }
        val totalMs = minutes.toLong() * 60_000L
        SleepTimerState.arm(totalMs)
        sleepTimerJob = serviceScope.launch {
            // A short timer still gets a graceful audible fade, while a long
            // timer reserves up to ten seconds for its goodbye.
            val fadeMs = min(SLEEP_FADE_MAX_MS, max(SLEEP_FADE_MIN_MS, totalMs / 5))
            var remaining = totalMs
            try {
                while (isActive && remaining > fadeMs) {
                    SleepTimerState.update(remaining)
                    val step = min(1_000L, remaining - fadeMs)
                    delay(step)
                    remaining -= step
                }
                if (!isActive || generation != sleepTimerGeneration) return@launch
                val startVolume = player.volume
                val steps = (fadeMs / 50L).coerceAtLeast(1L).toInt()
                for (index in 1..steps) {
                    if (!isActive || generation != sleepTimerGeneration) return@launch
                    delay(fadeMs / steps)
                    player.volume = startVolume * (1f - index.toFloat() / steps.toFloat())
                    SleepTimerState.update((fadeMs - fadeMs * index / steps).coerceAtLeast(0L))
                }
                player.volume = 0f
                if (generation != sleepTimerGeneration) return@launch
                player.pause()
                player.volume = 1f
                SleepTimerState.clear()
            } finally {
                if (generation == sleepTimerGeneration) {
                    player.volume = 1f
                    SleepTimerState.clear()
                }
            }
        }
    }

    private fun clearSleepTimer() {
        sleepTimerGeneration++
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1f
        SleepTimerState.clear()
    }

    private suspend fun fadeVolumeTo(target: Float, durationMs: Long) {
        val start = player.volume
        val steps = (durationMs / 40L).coerceIn(1L, 200L).toInt()
        repeat(steps) { index ->
            currentCoroutineContext().ensureActive()
            delay(durationMs / steps)
            player.volume = start + (target - start) * ((index + 1).toFloat() / steps)
        }
        player.volume = target
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Games commonly rotate while the floating window is visible. Keep
        // the lyric strip and its unlock handle inside the new screen bounds.
        desktopOverlay?.onScreenChanged()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        stopStatsTicker()
        sleepTimerGeneration++
        sleepTimerJob?.cancel()
        fadeJob?.cancel()
        overlayTranslationJob?.cancel()
        overlayTranslationJob = null
        overlayLyrics = null
        overlayTrackKey = null
        desktopOverlay?.hide()
        desktopOverlay = null
        SleepTimerState.clear()
        repository.flushStats()
        AudioReactiveSignal.reset()
        serviceScope.cancel()
        player.removeListener(listener)
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }

    private fun countPlayIfNeeded() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId.isNotBlank() && countedMediaId != mediaId) {
            repository.recordPlay(mediaId)
            countedMediaId = mediaId
        }
    }

    private fun updateKnownDuration() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val duration = player.duration
        if (duration > 0L && duration != C.TIME_UNSET) {
            repository.updateTrackDuration(mediaId, duration)
        }
    }

    private fun startStatsTicker() {
        if (statsJob?.isActive == true) return
        lastTickAt = SystemClock.elapsedRealtime()
        statsJob = serviceScope.launch {
            while (isActive && player.isPlaying) {
                delay(TICK_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTickAt
                lastTickAt = now
                val mediaId = player.currentMediaItem?.mediaId
                if (!mediaId.isNullOrBlank() && delta > 0L) {
                    repository.addListeningTime(mediaId, delta)
                }
            }
        }
    }

    private fun stopStatsTicker() {
        statsJob?.cancel()
        statsJob = null
        repository.flushStats()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val TICK_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_CHANNEL_ID = "catmusic_playback"
        private const val ACTION_TOGGLE_DESKTOP_LYRICS =
            "com.catmusic.app.action.TOGGLE_DESKTOP_LYRICS"
        private const val SLEEP_FADE_MAX_MS = 10_000L
        private const val SLEEP_FADE_MIN_MS = 1_500L
    }
}
