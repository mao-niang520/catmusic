package com.catmusic.app.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.catmusic.app.R
import com.catmusic.app.data.AdaptiveMotion
import com.catmusic.app.data.AudioVisualLevel
import com.catmusic.app.data.LyricDisplayLoader
import com.catmusic.app.data.LyricFrameRate
import com.catmusic.app.data.LyricTranslationMode
import com.catmusic.app.data.Track
import com.catmusic.app.playback.AudioEnergy
import com.catmusic.app.playback.AudioReactiveSignal
import com.catmusic.app.ui.components.CoverArt
import com.catmusic.app.ui.components.CoverStage
import com.catmusic.app.ui.components.GlassDropdownMenu
import com.catmusic.app.ui.components.ImmersiveLyrics
import com.catmusic.app.ui.components.LiquidGlassSurface
import com.catmusic.app.ui.components.LocalAdaptiveMotionNanos
import com.catmusic.app.ui.components.LocalSceneRipple
import com.catmusic.app.ui.components.MetaballFluidBackdrop
import com.catmusic.app.ui.components.PlayerUiSnapshot
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.util.ParsedLyrics
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.GlassBorder
import com.catmusic.app.util.audioFormatLabel
import com.catmusic.app.util.formatClock
import com.catmusic.app.util.formatSongDuration
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BackdropDust(
    val x: Float,
    val y: Float,
    val radius: Float,
    val phase: Float,
    val alpha: Float,
    val drift: Float
)

private data class FluidBlob(
    val x: Float,
    val y: Float,
    val radius: Float,
    val stretch: Float,
    val rotation: Float,
    val color: Color,
    val alpha: Float,
    val driftX: Float,
    val driftY: Float,
    val phase: Float
)


/**
 * The player is drawn on top of the library. Without a pointer-input node of
 * its own, taps on empty artwork/background areas fall through to the library
 * rows underneath (pausing or switching tracks). This node makes the player
 * the hit target while still letting its real controls receive events first.
 */
private fun Modifier.blockPlayerTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
            AdaptiveMotion.pulse()
        }
    }
}

@Composable
fun NowPlayingScreen(
    track: Track?,
    player: PlayerUiSnapshot,
    isLandscape: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onLyricOffsetChange: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onRequestArtwork: (Track) -> Unit,
    onOpenTrackInfo: (Track) -> Unit,
    onRequestLyrics: (Track) -> Unit,
    onToggleOrientation: () -> Unit,
    audioReactiveOn: Boolean,
    onToggleAudioReactive: () -> Unit,
    audioVisualLevel: AudioVisualLevel,
    lyricFrameRate: LyricFrameRate,
    translationMode: LyricTranslationMode,
    fadeInMs: Int,
    fadeOutMs: Int,
    onFadeInChange: (Int) -> Unit,
    onFadeOutChange: (Int) -> Unit,
    desktopLyricsEnabled: Boolean,
    onToggleDesktopLyrics: () -> Unit,
    sleepTimerRemainingMs: Long,
    onSetSleepTimer: (Int) -> Unit,
    onClearSleepTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fallbackAccent = remember(track?.id) {
        track?.let { coverPalette(it.id).last() } ?: Color(0xFFD8C2A6)
    }
    val accent = rememberArtworkAccent(track, fallbackAccent)
    val durationMs = when {
        player.durationMs > 0L -> player.durationMs
        track != null -> track.durationMs
        else -> 0L
    }
    val formatLabel = remember(track?.uri, track?.mimeType) { audioFormatLabel(track) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(track?.id, player.isPlaying) {
        AdaptiveMotion.pulse()
    }

    // A short fluid ripple under the translucent player menu. It deliberately
    // interrupts only the relaxing ambient field, not taps or playback.
    val menuRipple = remember { Animatable(1f) }
    val menuRippleState = remember(menuRipple) { menuRipple.asState() }
    val rippleScope = rememberCoroutineScope()
    val triggerMenuRipple: () -> Unit = {
        AdaptiveMotion.pulse()
        rippleScope.launch {
            menuRipple.snapTo(0f)
            menuRipple.animateTo(
                targetValue = 1f,
                animationSpec = tween(1350, easing = FastOutSlowInEasing)
            )
        }
        Unit
    }

    val handleRequestLyrics: () -> Unit = {
        track?.let {
            showLyrics = true
            onRequestLyrics(it)
        }
        Unit
    }

    // Turning 音浪 on opens the lyric stage; opening lyrics on its own never
    // changes the 音浪 preference.
    val handleToggleAudioReactive: () -> Unit = {
        if (!audioReactiveOn) showLyrics = true
        onToggleAudioReactive()
    }

    val coverEnter = fadeIn(tween(360, easing = FastOutSlowInEasing)) +
        scaleIn(tween(420, easing = FastOutSlowInEasing), initialScale = 0.99f)
    val coverExit = fadeOut(tween(260, easing = FastOutSlowInEasing)) +
        scaleOut(tween(300, easing = FastOutSlowInEasing), targetScale = 0.975f)
    val lyricsEnter = fadeIn(tween(440, easing = FastOutSlowInEasing)) +
        scaleIn(tween(460, easing = FastOutSlowInEasing), initialScale = 0.965f) +
        slideInVertically(tween(460, easing = FastOutSlowInEasing)) { it / 26 }
    val lyricsExit = fadeOut(tween(280, easing = FastOutSlowInEasing)) +
        scaleOut(tween(300, easing = FastOutSlowInEasing), targetScale = 0.985f) +
        slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it / 40 }

    CompositionLocalProvider(LocalSceneRipple provides menuRippleState) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Deliberately transparent: the animated GlassBackdropView lives
            // behind the Compose tree and is the scene both visible here and
            // sampled by every liquid-glass control.
            .blockPlayerTouches()
    ) {
        // The two scenes cross-fade instead of swapping instantly. The cover
        // is removed once it has faded out, so the WebView remains the only
        // heavy scene while lyrics are open.
        AnimatedVisibility(
            visible = !showLyrics,
            enter = coverEnter,
            exit = coverExit,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                MetaballFluidBackdrop()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    PlayerTopBar(
                        track = track,
                        formatLabel = formatLabel,
                        isLandscape = isLandscape,
                        audioReactiveOn = audioReactiveOn,
                        onClose = onClose,
                        onRequestArtwork = {
                            showLyrics = false
                            track?.let(onRequestArtwork)
                        },
                        onOpenTrackInfo = {
                            track?.let(onOpenTrackInfo)
                        },
                        onRequestLyrics = handleRequestLyrics,
                        onToggleAudioReactive = handleToggleAudioReactive,
                        onToggleOrientation = onToggleOrientation,
                        desktopLyricsEnabled = desktopLyricsEnabled,
                        onToggleDesktopLyrics = onToggleDesktopLyrics,
                        fadeInMs = fadeInMs,
                        fadeOutMs = fadeOutMs,
                        onFadeInChange = onFadeInChange,
                        onFadeOutChange = onFadeOutChange,
                        sleepTimerRemainingMs = sleepTimerRemainingMs,
                        onSetSleepTimer = onSetSleepTimer,
                        onClearSleepTimer = onClearSleepTimer,
                        onMenuOpened = triggerMenuRipple
                    )

                    if (isLandscape) {
                        LandscapePlayerBody(
                            track = track,
                            player = player,
                            accent = accent,
                            formatLabel = formatLabel,
                            durationMs = durationMs,
                            translationMode = translationMode,
                            effectsEnabled = audioReactiveOn,
                            onSeek = onSeek,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    } else {
                        PortraitPlayerBody(
                            track = track,
                            player = player,
                            accent = accent,
                            formatLabel = formatLabel,
                            effectsEnabled = audioReactiveOn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }

                    PlayerDock(
                        trackId = track?.id,
                        player = player,
                        accent = accent,
                        durationMs = durationMs,
                        isLandscape = isLandscape,
                        showLyrics = showLyrics,
                        audioReactiveOn = audioReactiveOn,
                        desktopLyricsEnabled = desktopLyricsEnabled,
                        onToggleLyrics = { showLyrics = !showLyrics },
                        onToggleDesktopLyrics = onToggleDesktopLyrics,
                        onToggleAudioReactive = handleToggleAudioReactive,
                        onSeek = onSeek,
                        onToggle = onToggle,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (isLandscape) 18.dp else 13.dp,
                                end = if (isLandscape) 18.dp else 13.dp,
                                top = if (isLandscape) 2.dp else 4.dp,
                                bottom = if (isLandscape) 6.dp else 7.dp
                            )
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showLyrics,
            enter = lyricsEnter,
            exit = lyricsExit,
            modifier = Modifier.fillMaxSize()
        ) {
            // Native gradient lyrics and the original CatMusic sound field get
            // a soft cross-fade when the 音浪 switch changes, rather than a
            // hard cut between renderers.
            Crossfade(
                targetState = audioReactiveOn,
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                label = "lyricsRenderer",
                modifier = Modifier.fillMaxSize()
            ) { reactive ->
                if (reactive) {
                    // With 音浪 enabled, the lyric stage is CatMusic's own
                    // WebGL sound field in a full-screen WebView. Hosting the
                    // whole lyric UI in the page keeps the canvas below every
                    // control without SurfaceView/z-order workarounds.
                    SoundFieldScreen(
                        track = track,
                        player = player,
                        durationMs = durationMs,
                        audioVisualLevel = audioVisualLevel,
                        frameRate = lyricFrameRate,
                        translationMode = translationMode,
                        coverAccent = accent,
                        onCloseLyrics = { showLyrics = false },
                        onToggle = onToggle,
                        onSeek = onSeek,
                        lyricOffsetMs = track?.lyricOffsetMs ?: 0L,
                        onLyricOffsetChange = onLyricOffsetChange,
                        onRequestLyrics = { current -> onRequestLyrics(current) },
                        onToggleAudioReactive = handleToggleAudioReactive,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ImmersiveLyricsScreen(
                        track = track,
                        player = player,
                        accent = accent,
                        formatLabel = formatLabel,
                        isLandscape = isLandscape,
                        durationMs = durationMs,
                        audioReactiveOn = audioReactiveOn,
                        translationMode = translationMode,
                        desktopLyricsEnabled = desktopLyricsEnabled,
                        onToggleDesktopLyrics = onToggleDesktopLyrics,
                        onCloseLyrics = { showLyrics = false },
                        onToggle = onToggle,
                        onSeek = onSeek,
                        lyricOffsetMs = track?.lyricOffsetMs ?: 0L,
                        onLyricOffsetChange = onLyricOffsetChange,
                        onRequestArtwork = { track?.let(onRequestArtwork) },
                        onOpenTrackInfo = { track?.let(onOpenTrackInfo) },
                        onRequestLyrics = handleRequestLyrics,
                        onToggleAudioReactive = handleToggleAudioReactive,
                        onToggleOrientation = onToggleOrientation,
                        fadeInMs = fadeInMs,
                        fadeOutMs = fadeOutMs,
                        onFadeInChange = onFadeInChange,
                        onFadeOutChange = onFadeOutChange,
                        sleepTimerRemainingMs = sleepTimerRemainingMs,
                        onSetSleepTimer = onSetSleepTimer,
                        onClearSleepTimer = onClearSleepTimer,
                        onMenuOpened = triggerMenuRipple,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun ImmersiveLyricsScreen(
    track: Track?,
    player: PlayerUiSnapshot,
    accent: Color,
    formatLabel: String,
    isLandscape: Boolean,
    durationMs: Long,
    audioReactiveOn: Boolean,
    translationMode: LyricTranslationMode,
    desktopLyricsEnabled: Boolean,
    onToggleDesktopLyrics: () -> Unit,
    onCloseLyrics: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    lyricOffsetMs: Long,
    onLyricOffsetChange: (Long) -> Unit,
    onRequestArtwork: () -> Unit,
    onOpenTrackInfo: () -> Unit,
    onRequestLyrics: () -> Unit,
    onToggleAudioReactive: () -> Unit,
    onToggleOrientation: () -> Unit,
    fadeInMs: Int,
    fadeOutMs: Int,
    onFadeInChange: (Int) -> Unit,
    onFadeOutChange: (Int) -> Unit,
    onMenuOpened: () -> Unit,
    sleepTimerRemainingMs: Long,
    onSetSleepTimer: (Int) -> Unit,
    onClearSleepTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCalibration by rememberSaveable(track?.id) { mutableStateOf(false) }
    val hasLyrics = !track?.lyricsFile.isNullOrBlank()
    Box(
        modifier = modifier
            .fillMaxSize()
            .blockPlayerTouches()
    ) {
        if (isLandscape) {
            FluidBackdrop(
                accent = accent,
                isPlaying = player.isPlaying,
                effectsEnabled = audioReactiveOn
            )
        } else {
            LyricsBackdrop(accent = accent)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            PlayerTopBar(
                track = track,
                formatLabel = formatLabel,
                isLandscape = isLandscape,
                audioReactiveOn = audioReactiveOn,
                minimal = true,
                closeDescription = "返回封面播放页",
                onClose = onCloseLyrics,
                onRequestArtwork = onRequestArtwork,
                onOpenTrackInfo = onOpenTrackInfo,
                onRequestLyrics = onRequestLyrics,
                onToggleAudioReactive = onToggleAudioReactive,
                onToggleOrientation = onToggleOrientation,
                onMenuOpened = onMenuOpened,
                desktopLyricsEnabled = desktopLyricsEnabled,
                onToggleDesktopLyrics = onToggleDesktopLyrics,
                fadeInMs = fadeInMs,
                fadeOutMs = fadeOutMs,
                onFadeInChange = onFadeInChange,
                onFadeOutChange = onFadeOutChange,
                lyricCalibrationEnabled = hasLyrics,
                lyricOffsetMs = lyricOffsetMs,
                onOpenLyricCalibration = { showCalibration = true },
                sleepTimerRemainingMs = sleepTimerRemainingMs,
                onSetSleepTimer = onSetSleepTimer,
                onClearSleepTimer = onClearSleepTimer
            )
            if (isLandscape) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val coverSize = minOf(maxHeight * 0.94f, maxWidth * 0.27f)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(end = 26.dp)
                        ) {
                            Text(
                                text = track?.title ?: "歌词",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = CatCream.copy(alpha = 0.94f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track?.artist.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = Color.White.copy(alpha = 0.40f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                ImmersiveLyrics(
                                    track = track,
                                    player = player,
                                    accent = accent,
                                    isLandscape = true,
                                    offsetMs = lyricOffsetMs,
                                    translationMode = translationMode,
                                    onSeek = onSeek,
                                    onImportLyrics = onRequestLyrics,
                                    leftAligned = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        LandscapeCover(
                            track = track,
                            accent = accent,
                            isPlaying = player.isPlaying,
                            effectsEnabled = audioReactiveOn,
                            modifier = Modifier.size(coverSize)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    ImmersiveLyrics(
                        track = track,
                        player = player,
                        accent = accent,
                        isLandscape = false,
                        offsetMs = lyricOffsetMs,
                        translationMode = translationMode,
                        onSeek = onSeek,
                        onImportLyrics = onRequestLyrics,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            if (showCalibration) {
                LyricCalibrationBar(
                    offsetMs = lyricOffsetMs,
                    accent = accent,
                    isLandscape = isLandscape,
                    onOffsetChange = onLyricOffsetChange,
                    onClose = { showCalibration = false }
                )
            } else {
                ImmersiveLyricsBottomBar(
                    trackId = track?.id,
                    player = player,
                    accent = accent,
                    durationMs = durationMs,
                    isLandscape = isLandscape,
                    audioReactiveOn = audioReactiveOn,
                    onToggle = onToggle,
                    onSeek = onSeek,
                    onToggleAudioReactive = onToggleAudioReactive
                )
            }
        }
    }
}

@Composable
private fun LyricsBackdrop(accent: Color) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF8A4A2F).copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.27f, size.height * 0.43f),
                    radius = size.width * 0.70f
                ),
                radius = size.width * 0.70f,
                center = Offset(size.width * 0.27f, size.height * 0.43f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.18f, size.height * 0.28f),
                    radius = size.width * 0.55f
                ),
                radius = size.width * 0.55f,
                center = Offset(size.width * 0.18f, size.height * 0.28f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.07f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.50f, size.height * 0.44f),
                    radius = size.width * 0.52f
                ),
                radius = size.width * 0.52f,
                center = Offset(size.width * 0.50f, size.height * 0.44f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF6F9BC4).copy(alpha = 0.11f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.88f, size.height * 0.58f),
                    radius = size.width * 0.52f
                ),
                radius = size.width * 0.52f,
                center = Offset(size.width * 0.88f, size.height * 0.58f)
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.075f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.50f, size.height * 0.46f),
                    radius = size.width * 0.40f
                ),
                radius = size.width * 0.40f,
                center = Offset(size.width * 0.50f, size.height * 0.46f)
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.22f),
                    0.28f to Color.Transparent,
                    0.74f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.30f)
                )
            )
        }
    }
}

@Composable
private fun ImmersiveLyricsBottomBar(
    trackId: String?,
    player: PlayerUiSnapshot,
    accent: Color,
    durationMs: Long,
    isLandscape: Boolean,
    audioReactiveOn: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleAudioReactive: () -> Unit
) {
    var dragFraction by remember(trackId) { mutableStateOf<Float?>(null) }
    val playbackFraction = if (durationMs > 0L) {
        (player.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = dragFraction ?: playbackFraction

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .bouncyClickable(pressScale = 0.86f, onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = if (player.isPlaying) "暂停" else "播放",
                    tint = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        ThinSeekBar(
            fraction = shownFraction,
            accent = accent,
            isScrubbing = dragFraction != null,
            onScrub = { fraction -> dragFraction = fraction },
            onFinishScrub = {
                dragFraction?.let { fraction ->
                    if (durationMs > 0L) {
                        onSeek((fraction * durationMs).toLong())
                    }
                }
                dragFraction = null
            },
            onCancelScrub = { dragFraction = null },
            modifier = Modifier
                .width(if (isLandscape) 260.dp else 180.dp)
                .height(22.dp),
            minimal = true
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (audioReactiveOn) accent.copy(alpha = 0.14f)
                        else Color.White.copy(alpha = 0.035f)
                    )
                    .border(
                        1.dp,
                        if (audioReactiveOn) accent.copy(alpha = 0.25f)
                        else Color.White.copy(alpha = 0.07f),
                        CircleShape
                    )
                    .bouncyClickable(pressScale = 0.88f, onClick = onToggleAudioReactive),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chart),
                    contentDescription = if (audioReactiveOn) "关闭音浪" else "开启音浪",
                    tint = if (audioReactiveOn) {
                        accent.copy(alpha = 0.90f)
                    } else {
                        Color.White.copy(alpha = 0.36f)
                    },
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private const val LYRIC_OFFSET_STEP_MS = 100L
private const val LYRIC_OFFSET_LIMIT_MS = 5_000L

private fun formatLyricOffset(offsetMs: Long): String {
    val rounded = ((kotlin.math.abs(offsetMs) + 50L) / 100L) * 100L
    val whole = rounded / 1_000L
    val tenth = (rounded % 1_000L) / 100L
    val sign = when {
        offsetMs > 0L -> "+"
        offsetMs < 0L -> "−"
        else -> ""
    }
    return "$sign$whole.$tenth 秒"
}

@Composable
private fun LyricCalibrationBar(
    offsetMs: Long,
    accent: Color,
    isLandscape: Boolean,
    onOffsetChange: (Long) -> Unit,
    onClose: () -> Unit
) {
    val lowerOffset = (offsetMs - LYRIC_OFFSET_STEP_MS)
        .coerceAtLeast(-LYRIC_OFFSET_LIMIT_MS)
    val higherOffset = (offsetMs + LYRIC_OFFSET_STEP_MS)
        .coerceAtMost(LYRIC_OFFSET_LIMIT_MS)

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "歌词校准",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp
                ),
                color = Color.White.copy(alpha = 0.42f)
            )
            Spacer(Modifier.width(14.dp))
            LyricOffsetValue(offsetMs)
            Spacer(Modifier.weight(1f))
            CalibrationPill("−0.1", onClick = { onOffsetChange(lowerOffset) })
            Spacer(Modifier.width(6.dp))
            CalibrationPill("归零", onClick = { onOffsetChange(0L) })
            Spacer(Modifier.width(6.dp))
            CalibrationPill("+0.1", onClick = { onOffsetChange(higherOffset) })
            Spacer(Modifier.width(10.dp))
            CalibrationPill("完成", accent = accent, primary = true, onClick = onClose)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "歌词校准",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp
                    ),
                    color = Color.White.copy(alpha = 0.42f)
                )
                Spacer(Modifier.weight(1f))
                LyricOffsetValue(offsetMs)
                Spacer(Modifier.width(12.dp))
                CalibrationPill("完成", accent = accent, primary = true, onClick = onClose)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalibrationPill("−0.1 秒", onClick = { onOffsetChange(lowerOffset) })
                CalibrationPill("归零", onClick = { onOffsetChange(0L) })
                CalibrationPill("+0.1 秒", onClick = { onOffsetChange(higherOffset) })
            }
        }
    }
}

@Composable
private fun LyricOffsetValue(offsetMs: Long) {
    Text(
        text = formatLyricOffset(offsetMs),
        style = MaterialTheme.typography.titleSmall.copy(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        ),
        color = Color.White.copy(alpha = 0.90f),
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 64.dp)
    )
}

@Composable
private fun CalibrationPill(
    label: String,
    accent: Color = Color.White,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(
                if (primary) accent.copy(alpha = 0.16f)
                else Color.White.copy(alpha = 0.055f)
            )
            .border(
                1.dp,
                if (primary) accent.copy(alpha = 0.26f)
                else Color.White.copy(alpha = 0.065f),
                shape
            )
            .bouncyClickable(pressScale = 0.90f, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            color = if (primary) {
                accent.copy(alpha = 0.92f)
            } else {
                Color.White.copy(alpha = 0.72f)
            },
            maxLines = 1
        )
    }
}

@Composable
private fun PortraitPlayerBody(
    track: Track?,
    player: PlayerUiSnapshot,
    accent: Color,
    formatLabel: String,
    effectsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val coverSide = minOf(maxWidth * 0.96f, maxHeight * 0.74f)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CoverStage(
                track = track,
                accent = accent,
                isPlaying = player.isPlaying,
                effectsEnabled = effectsEnabled,
                modifier = Modifier.size(coverSide)
            )
            Spacer(Modifier.height(10.dp))
            TrackMeta(
                track = track,
                formatLabel = formatLabel,
                accent = accent,
                centered = true,
                compact = false,
                modifier = Modifier.padding(horizontal = 30.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LandscapePlayerBody(
    track: Track?,
    player: PlayerUiSnapshot,
    accent: Color,
    formatLabel: String,
    durationMs: Long,
    translationMode: LyricTranslationMode,
    effectsEnabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val dense = maxHeight < 245.dp
        val titleSize = when {
            maxHeight < 225.dp -> 24.sp
            maxHeight < 290.dp -> 28.sp
            else -> 33.sp
        }
        val coverSize = minOf(maxHeight * 0.95f, maxWidth * 0.34f)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 26.dp),
                verticalArrangement = Arrangement.Center
            ) {
                LandscapeTrackHeading(
                    track = track,
                    formatLabel = formatLabel,
                    accent = accent,
                    titleSize = titleSize,
                    dense = dense
                )
                Spacer(Modifier.height(if (dense) 8.dp else 12.dp))
                LandscapeLyricPreview(
                    track = track,
                    positionMs = player.positionMs,
                    durationMs = durationMs,
                    translationMode = translationMode,
                    maxLines = if (dense) 3 else 4,
                    dense = dense,
                    accent = accent,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            LandscapeCover(
                track = track,
                accent = accent,
                isPlaying = player.isPlaying,
                effectsEnabled = effectsEnabled,
                modifier = Modifier.size(coverSize)
            )
        }
    }
}

@Composable
private fun LandscapeTrackHeading(
    track: Track?,
    formatLabel: String,
    accent: Color,
    titleSize: androidx.compose.ui.unit.TextUnit,
    dense: Boolean
) {
    val album = track?.album?.takeIf { it.isNotBlank() && it != "未知专辑" }
    val overline = buildString {
        if (album != null) {
            append(album)
            append("  ·  ")
        }
        append(formatLabel.ifBlank { "本地音乐" })
    }
    val shadow = Shadow(
        color = Color.Black.copy(alpha = 0.45f),
        offset = Offset(0f, 2f),
        blurRadius = 16f
    )
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = overline.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                letterSpacing = 1.7.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = accent.copy(alpha = 0.80f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(if (dense) 4.dp else 6.dp))
        Text(
            text = track?.title ?: "还没有正在播放的歌曲",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = titleSize,
                lineHeight = (titleSize.value * 1.08f).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.6).sp,
                shadow = shadow
            ),
            color = CatCream.copy(alpha = 0.97f),
            maxLines = if (dense) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = track?.artist ?: "从音乐馆选择一首歌",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (dense) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                shadow = shadow
            ),
            color = Color.White.copy(alpha = 0.50f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(if (dense) 6.dp else 9.dp))
        Box(
            Modifier
                .width(if (dense) 26.dp else 34.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.72f), Color.Transparent)
                    )
                )
        )
    }
}

/**
 * A live left-hand lyric rail for the cover page. It only reads the local
 * lyric file (no online translation is triggered here) and keeps the current
 * line plus its neighbours visible instead of duplicating the full stage.
 */
@Composable
private fun LandscapeLyricPreview(
    track: Track?,
    positionMs: Long,
    durationMs: Long,
    translationMode: LyricTranslationMode,
    maxLines: Int,
    dense: Boolean,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val parsed by produceState<ParsedLyrics?>(
        initialValue = null,
        track?.lyricsFile,
        translationMode
    ) {
        value = LyricDisplayLoader.load(context, track, translationMode)
    }
    val lyrics = parsed
    if (lyrics == null || lyrics.lines.isEmpty()) {
        if (!track?.lyricsFile.isNullOrBlank()) {
            Spacer(modifier.height(if (dense) 34.dp else 44.dp))
        } else {
            Row(
                modifier = modifier.height(if (dense) 34.dp else 44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "轻触下方「词」载入歌词",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = Color.White.copy(alpha = 0.30f)
                )
            }
        }
        return
    }

    val offsetMs = track?.lyricOffsetMs ?: 0L
    val active = lyrics.activeIndex(
        (positionMs - offsetMs).coerceAtLeast(0L)
    )
    val maxIndex = lyrics.lines.lastIndex
    var selectedIndex by remember(track?.id, translationMode) {
        mutableStateOf<Int?>(null)
    }
    var isDragging by remember(track?.id, translationMode) {
        mutableStateOf(false)
    }
    var isTouching by remember(track?.id, translationMode) {
        mutableStateOf(false)
    }
    val latestSelected = rememberUpdatedState(selectedIndex)
    val latestActive = rememberUpdatedState(if (active < 0) 0 else active)
    val latestOffset = rememberUpdatedState(offsetMs)
    val latestDuration = rememberUpdatedState(durationMs)
    val latestOnSeek = rememberUpdatedState(onSeek)

    LaunchedEffect(maxIndex) {
        selectedIndex = selectedIndex?.coerceIn(0, maxIndex)
    }

    // A drag previews a lyric without touching playback. The preview quietly
    // yields back to the live position after three seconds unless the user
    // taps it, and only that tap commits the seek.
    LaunchedEffect(selectedIndex, isDragging, isTouching) {
        val selected = selectedIndex ?: return@LaunchedEffect
        // Keep the preview alive while a finger is on it, so a tap that lands
        // just before the deadline is not swallowed by the countdown.
        if (!isDragging && !isTouching) {
            delay(3_000L)
            if (selectedIndex == selected) selectedIndex = null
        }
    }

    val seekModifier = if (lyrics.hasTiming) {
        Modifier.pointerInput(lyrics, dense) {
            val lineStep = (if (dense) 30.dp else 36.dp).toPx().coerceAtLeast(1f)
            val tapSlop = viewConfiguration.touchSlop.coerceAtLeast(6f)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                isTouching = true
                val initialSelection = latestSelected.value
                val startIndex = (initialSelection ?: latestActive.value)
                    .coerceIn(0, maxIndex)
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (change.pressed) {
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!dragging &&
                                abs(dy) > tapSlop &&
                                abs(dy) > abs(dx)
                            ) {
                                dragging = true
                                isDragging = true
                                selectedIndex = initialSelection ?: startIndex
                            }
                            if (dragging) {
                                // Swipe up for later lyrics, down for earlier.
                                val steps = (-dy / lineStep).roundToInt()
                                val target = (startIndex + steps).coerceIn(0, maxIndex)
                                if (selectedIndex != target) selectedIndex = target
                                change.consume()
                            }
                        } else {
                            if (dragging) {
                                change.consume()
                            } else {
                                val stayed = abs(change.position.x - down.position.x) <= tapSlop &&
                                    abs(change.position.y - down.position.y) <= tapSlop
                                val chosen = latestSelected.value
                                if (stayed && chosen != null) {
                                    lyrics.lines.getOrNull(chosen)?.let { line ->
                                        val raw = (line.timeMs + latestOffset.value)
                                            .coerceAtLeast(0L)
                                        val target = if (latestDuration.value > 0L) {
                                            raw.coerceAtMost(latestDuration.value)
                                        } else {
                                            raw
                                        }
                                        latestOnSeek.value(target)
                                    }
                                    selectedIndex = null
                                    change.consume()
                                }
                            }
                            break
                        }
                    }
                } finally {
                    if (dragging) isDragging = false
                    isTouching = false
                }
            }
        }
    } else {
        Modifier
    }

    val highlighted = selectedIndex ?: (if (active < 0) 0 else active)
    val anchor = highlighted.coerceIn(0, maxIndex)
    val count = minOf(maxLines, lyrics.lines.size)
    val start = (anchor - (count - 1) / 2)
        .coerceIn(0, (lyrics.lines.size - count).coerceAtLeast(0))
    val activeSize = if (dense) 17.5.sp else 20.sp
    val idleSize = if (dense) 12.sp else 13.5.sp
    val glow = Shadow(
        color = Color.White.copy(alpha = 0.32f),
        offset = Offset.Zero,
        blurRadius = 24f
    )
    val selectedLine = selectedIndex?.let { lyrics.lines.getOrNull(it) }
    val selectedTimeMs = selectedLine?.let { line ->
        val raw = (line.timeMs + offsetMs).coerceAtLeast(0L)
        if (durationMs > 0L) raw.coerceAtMost(durationMs) else raw
    }

    Box(modifier.then(seekModifier)) {
        Column(Modifier.fillMaxWidth()) {
            if (selectedIndex != null && selectedTimeMs != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (dense) 21.dp else 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatClock(selectedTimeMs),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = if (dense) 15.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.2.sp
                        ),
                        color = accent
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (isDragging) {
                            "上滑快进 · 下滑回退"
                        } else {
                            "点击跳转"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (dense) 9.sp else 10.sp
                        ),
                        color = Color.White.copy(alpha = 0.48f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    if (!isDragging) {
                        Text(
                            text = "3 秒后返回",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = if (dense) 8.5.sp else 9.5.sp
                            ),
                            color = Color.White.copy(alpha = 0.28f),
                            maxLines = 1
                        )
                    }
                }
            }
            for (index in start until start + count) {
                val line = lyrics.lines[index]
                val isActive = index == highlighted
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (isActive) activeSize else idleSize,
                        lineHeight = if (isActive) {
                            (activeSize.value * 1.24f).sp
                        } else {
                            (idleSize.value * 1.30f).sp
                        },
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = 0.2.sp,
                        shadow = if (isActive) glow else null
                    ),
                    color = if (isActive) {
                        Color(0xFFFFFBF6)
                    } else {
                        Color.White.copy(alpha = if (index < highlighted) 0.26f else 0.40f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isActive && !line.translation.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = line.translation.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = if (dense) 10.sp else 11.sp,
                            lineHeight = if (dense) 13.sp else 14.sp
                        ),
                        color = Color.White.copy(alpha = 0.56f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(if (isActive) 5.dp else 4.dp))
            }
        }
    }
}

@Composable
private fun LandscapeCover(
    track: Track?,
    accent: Color,
    isPlaying: Boolean,
    effectsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val corner = RoundedCornerShape(26.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.16f),
                        accent.copy(alpha = 0.04f),
                        accent.copy(alpha = 0f)
                    ),
                    center = center,
                    radius = size.minDimension * 0.62f
                ),
                radius = size.minDimension * 0.62f,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.065f),
                radius = size.minDimension * 0.475f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize(0.94f)
                .shadow(
                    elevation = 28.dp,
                    shape = corner,
                    ambientColor = Color.Black,
                    spotColor = Color.Black
                )
                .clip(corner)
                .background(Color(0xFF0A0B0D))
                .border(1.dp, Color.White.copy(alpha = 0.14f), corner)
                .graphicsLayer {
                    val audio = if (isPlaying && effectsEnabled) {
                        AudioReactiveSignal.current
                    } else {
                        AudioEnergy()
                    }
                    val scale = 1f +
                        (if (audio.active) audio.level else 0f) * 0.014f +
                        (if (audio.active) audio.beat else 0f) * 0.011f
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            CoverArt(
                artworkFile = track?.artworkFile,
                seed = track?.id ?: "catmusic",
                cornerRadius = 26.dp,
                showBorder = false,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.045f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.18f)
                            )
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.dp, Color.White.copy(alpha = 0.075f), corner)
            )
        }
    }
}

@Composable
private fun CinematicBackdrop(
    accent: Color,
    isPlaying: Boolean,
    effectsEnabled: Boolean
) {
    // The colour field, parallax and motes now live in GlassBackdropView so
    // the liquid-glass lens can actually refract them. This layer stays fully
    // transparent and only adds a whisper of accent light plus the existing
    // audio-reactive dust on top.
    val sceneRipple = LocalSceneRipple.current
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.50f, size.height * 0.24f),
                    radius = size.width * 0.92f
                ),
                radius = size.width * 0.92f,
                center = Offset(size.width * 0.50f, size.height * 0.24f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.045f), Color.Transparent),
                    center = Offset(size.width * 0.78f, size.height * 0.72f),
                    radius = size.width * 0.55f
                ),
                radius = size.width * 0.55f,
                center = Offset(size.width * 0.78f, size.height * 0.72f)
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                    center = center,
                    radius = size.maxDimension * 0.72f
                )
            )
            drawSceneRipple(accent = accent, progress = sceneRipple.value)
        }
        BackdropDust(
            isPlaying = isPlaying,
            effectsEnabled = effectsEnabled,
            accent = accent,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * The landscape scene: a very slow, dark gradient fluid rather than a blurred
 * copy of the artwork. Large teal, slate and accent colour fields drift into
 * one another with no hard rings or road lines, so the cover and the glass
 * controls sit in a calm, continuous surface.
 */
@Composable
private fun FluidBackdrop(
    accent: Color,
    isPlaying: Boolean,
    effectsEnabled: Boolean
) {
    val motionNanos = LocalAdaptiveMotionNanos.current
    val sceneRipple = LocalSceneRipple.current
    val blobs = remember(accent) {
        listOf(
            FluidBlob(
                x = 0.12f, y = 0.34f, radius = 0.62f, stretch = 0.68f,
                rotation = -16f, color = Color(0xFF3B8A87), alpha = 0.25f,
                driftX = 0.028f, driftY = 0.035f, phase = 0f
            ),
            FluidBlob(
                x = 0.61f, y = 0.18f, radius = 0.50f, stretch = 0.78f,
                rotation = 24f, color = Color(0xFF59696C), alpha = 0.21f,
                driftX = 0.035f, driftY = 0.022f, phase = 1.9f
            ),
            FluidBlob(
                x = 0.79f, y = 0.70f, radius = 0.47f, stretch = 0.70f,
                rotation = -8f, color = accent, alpha = 0.19f,
                driftX = 0.026f, driftY = 0.030f, phase = 3.4f
            ),
            FluidBlob(
                x = 0.44f, y = 0.91f, radius = 0.50f, stretch = 0.56f,
                rotation = 12f, color = Color(0xFFB3DCD5), alpha = 0.075f,
                driftX = 0.040f, driftY = 0.018f, phase = 5.1f
            ),
            FluidBlob(
                x = 0.92f, y = 0.16f, radius = 0.34f, stretch = 0.86f,
                rotation = 38f, color = Color(0xFF1D5E5E), alpha = 0.26f,
                driftX = 0.020f, driftY = 0.028f, phase = 2.7f
            ),
            FluidBlob(
                x = 0.30f, y = 0.56f, radius = 0.30f, stretch = 1.10f,
                rotation = -30f, color = Color(0xFF7E8F90), alpha = 0.10f,
                driftX = 0.032f, driftY = 0.024f, phase = 4.3f
            )
        )
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val audio = if (isPlaying && effectsEnabled) {
                AudioReactiveSignal.current
            } else {
                AudioEnergy()
            }
            val level = if (audio.active) audio.level else 0f
            val bass = if (audio.active) audio.bass else 0f
            val twoPi = 2f * PI.toFloat()
            val seconds = motionNanos.value / 1_000_000_000f
            val drift = (seconds / 42f) % 1f
            val breathe = 0.5f + 0.5f * sin(seconds * twoPi / 22f)
            val phase = drift * twoPi

            drawRect(
                brush = Brush.linearGradient(
                    0f to Color(0xFF061114),
                    0.46f to Color(0xFF0A1D1F),
                    1f to Color(0xFF040809),
                    start = Offset(0f, 0f),
                    end = Offset(width, height)
                )
            )

            // A broad, almost imperceptible light wash keeps the base gradient
            // moving even between the slower colour-field drifts.
            val wash = 0.5f + 0.5f * sin(seconds * twoPi / 54f)
            drawRect(
                brush = Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0f),
                        accent.copy(alpha = 0.05f),
                        accent.copy(alpha = 0f)
                    ),
                    start = Offset(width * (0.08f + wash * 0.20f), 0f),
                    end = Offset(width * (0.60f + wash * 0.18f), height)
                )
            )

            // Big, soft colour fields. A radial brush is squashed into an oval
            // so the edge is genuinely transparent instead of a hard clip.
            blobs.forEach { blob ->
                val localPhase = phase + blob.phase
                val center = Offset(
                    width * (blob.x + sin(localPhase) * blob.driftX),
                    height * (blob.y + cos(localPhase * 0.83f) * blob.driftY)
                )
                val radius = size.maxDimension * blob.radius *
                    (1f + level * 0.045f + bass * 0.018f + breathe * 0.016f)
                val alpha = (blob.alpha *
                    (0.84f + breathe * 0.10f + level * 0.22f + bass * 0.08f))
                    .coerceIn(0f, 0.40f)
                withTransform({
                    translate(center.x, center.y)
                    rotate(blob.rotation + sin(localPhase) * 4.5f, Offset.Zero)
                    scale(1f, blob.stretch, Offset.Zero)
                }) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                blob.color.copy(alpha = alpha),
                                blob.color.copy(alpha = alpha * 0.42f),
                                blob.color.copy(alpha = 0f)
                            ),
                            center = Offset.Zero,
                            radius = radius
                        ),
                        radius = radius,
                        center = Offset.Zero
                    )
                }
            }

            // No hard rings or road strokes: the motion should read as one
            // continuous gradient rather than a graphic layer.
            // Keep the typography readable at the edges while leaving the
            // centre alive.
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.30f),
                    0.34f to Color.Transparent,
                    0.82f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.16f)
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.30f),
                    0.24f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.40f)
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
                    center = Offset(width * 0.58f, height * 0.46f),
                    radius = size.maxDimension * 0.76f
                )
            )
            drawSceneRipple(accent = accent, progress = sceneRipple.value)
        }
        BackdropDust(
            isPlaying = isPlaying,
            effectsEnabled = effectsEnabled,
            accent = accent,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * A short, soft disturbance of the fluid when the player menu opens. It reads
 * as a liquid surface being tapped rather than as another permanent effect.
 */
private fun DrawScope.drawSceneRipple(accent: Color, progress: Float) {
    if (progress >= 0.999f) return
    val p = progress.coerceIn(0f, 1f)
    val fade = (1f - p) * (1f - p)
    val origin = Offset(size.width * 0.87f, size.height * 0.14f)
    val radius = size.minDimension * (0.035f + p * 0.78f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.15f * fade),
                accent.copy(alpha = 0.10f * fade),
                accent.copy(alpha = 0f)
            ),
            center = origin,
            radius = radius
        ),
        radius = radius,
        center = origin
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.28f * fade),
        radius = radius,
        center = origin,
        style = Stroke(width = (0.6f + 2.2f * fade).dp.toPx())
    )
    drawCircle(
        color = accent.copy(alpha = 0.20f * fade),
        radius = radius * 1.22f,
        center = origin,
        style = Stroke(width = (0.4f + 1.2f * fade).dp.toPx())
    )
}

@Composable
private fun BackdropDust(
    isPlaying: Boolean,
    effectsEnabled: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val dots = remember {
        val random = Random(5077)
        List(30) {
            BackdropDust(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.35f + random.nextFloat() * 0.95f,
                phase = random.nextFloat(),
                alpha = 0.035f + random.nextFloat() * 0.10f,
                drift = 0.004f + random.nextFloat() * 0.012f
            )
        }
    }
    val motionNanos = LocalAdaptiveMotionNanos.current
    val twoPi = 2f * PI.toFloat()

    Canvas(modifier) {
        val time = (motionNanos.value / 1_000_000_000f / 64f) % 1f
        val audio = if (isPlaying && effectsEnabled) {
            AudioReactiveSignal.current
        } else {
            AudioEnergy()
        }
        val level = if (audio.active) audio.level else 0f
        val treble = if (audio.active) audio.treble else 0f
        dots.forEach { dot ->
            val x = dot.x + sin(
                ((time + dot.phase) * twoPi).toDouble()
            ).toFloat() * dot.drift
            val y = (dot.y - time * dot.drift * 1.4f + 1f) % 1f
            val twinkle = 0.58f + 0.42f * (
                sin(((time * 2f + dot.phase) * twoPi).toDouble())
                    .toFloat() * 0.5f + 0.5f
                )
            val alpha = (
                dot.alpha * twinkle * (0.72f + level * 0.55f + treble * 0.25f)
                ).coerceIn(0f, 0.30f)
            drawCircle(
                color = if (dot.alpha > 0.08f) {
                    Color.White.copy(alpha = alpha)
                } else {
                    accent.copy(alpha = alpha * 0.8f)
                },
                radius = dot.radius.dp.toPx() * (1f + level * 0.35f),
                center = Offset(x * size.width, y * size.height)
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    track: Track?,
    formatLabel: String,
    isLandscape: Boolean,
    audioReactiveOn: Boolean,
    minimal: Boolean = false,
    closeDescription: String = "收起播放器",
    onClose: () -> Unit,
    onRequestArtwork: () -> Unit,
    onOpenTrackInfo: () -> Unit,
    onRequestLyrics: () -> Unit,
    onToggleAudioReactive: () -> Unit,
    onToggleOrientation: () -> Unit,
    desktopLyricsEnabled: Boolean,
    onToggleDesktopLyrics: () -> Unit,
    fadeInMs: Int,
    fadeOutMs: Int,
    onFadeInChange: (Int) -> Unit,
    onFadeOutChange: (Int) -> Unit,
    onMenuOpened: () -> Unit = {},
    lyricCalibrationEnabled: Boolean = false,
    lyricOffsetMs: Long = 0L,
    onOpenLyricCalibration: (() -> Unit)? = null,
    sleepTimerRemainingMs: Long = 0L,
    onSetSleepTimer: (Int) -> Unit = {},
    onClearSleepTimer: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showSleepDialog by rememberSaveable { mutableStateOf(false) }
    var showFadeDialog by rememberSaveable { mutableStateOf(false) }
    val controlSize = if (isLandscape) 36.dp else 42.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isLandscape) 18.dp else 20.dp,
                vertical = if (isLandscape) 3.dp else 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleAction(
            iconRes = R.drawable.ic_arrow_down,
            description = closeDescription,
            size = controlSize,
            iconSize = if (isLandscape) 18.dp else 21.dp,
            subtle = minimal,
            onClick = onClose
        )
        Spacer(Modifier.weight(1f))
        if (!minimal) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 2.8.sp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = PlayerMenuTextShadow
                    ),
                    color = Color.White.copy(alpha = 0.72f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (sleepTimerRemainingMs > 0L) {
                        "定时暂停 · ${formatSleepTimerRemaining(sleepTimerRemainingMs)}"
                    } else {
                        "本地音乐 · $formatLabel"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        shadow = PlayerMenuTextShadow
                    ),
                    color = if (sleepTimerRemainingMs > 0L) {
                        CatPeach.copy(alpha = 0.86f)
                    } else {
                        Color.White.copy(alpha = 0.42f)
                    }
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            CircleAction(
                iconRes = R.drawable.ic_more,
                description = "更多播放选项",
                size = controlSize,
                iconSize = if (isLandscape) 17.dp else 20.dp,
                subtle = minimal,
                onClick = {
                    menuExpanded = true
                    onMenuOpened()
                }
            )
            GlassDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                revealBackdrop = true,
                modifier = Modifier.widthIn(min = 232.dp)
            ) {
                DropdownMenuItem(
                    text = { PlayerMenuLabel("更换专辑封面") },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_image)
                    },
                    enabled = track != null,
                    onClick = {
                        menuExpanded = false
                        onRequestArtwork()
                    }
                )
                DropdownMenuItem(
                    text = { PlayerMenuLabel("歌曲信息") },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_info)
                    },
                    enabled = track != null,
                    onClick = {
                        menuExpanded = false
                        onOpenTrackInfo()
                    }
                )
                DropdownMenuItem(
                    text = {
                        PlayerMenuLabel(
                            if (track?.lyricsFile.isNullOrBlank()) "导入歌词" else "更换歌词"
                        )
                    },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_lyrics)
                    },
                    enabled = track != null,
                    onClick = {
                        menuExpanded = false
                        onRequestLyrics()
                    }
                )
                if (minimal && onOpenLyricCalibration != null) {
                    DropdownMenuItem(
                        text = { PlayerMenuLabel("歌词校准") },
                        leadingIcon = {
                            PlayerMenuIcon(R.drawable.ic_lyrics)
                        },
                        trailingIcon = {
                            PlayerMenuValue(
                                text = if (lyricOffsetMs == 0L) {
                                    "未校准"
                                } else {
                                    formatLyricOffset(lyricOffsetMs)
                                },
                                color = if (lyricOffsetMs == 0L) {
                                    Color.White.copy(alpha = 0.38f)
                                } else {
                                    Color.White.copy(alpha = 0.78f)
                                }
                            )
                        },
                        enabled = lyricCalibrationEnabled,
                        onClick = {
                            menuExpanded = false
                            onOpenLyricCalibration()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { PlayerMenuLabel("定时暂停") },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_timer)
                    },
                    trailingIcon = {
                        PlayerMenuValue(
                            text = if (sleepTimerRemainingMs > 0L) {
                                formatSleepTimerRemaining(sleepTimerRemainingMs)
                            } else {
                                "关"
                            },
                            color = if (sleepTimerRemainingMs > 0L) {
                                CatPeach
                            } else {
                                Color.White.copy(alpha = 0.42f)
                            }
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        showSleepDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { PlayerMenuLabel("播放渐入 / 渐出") },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_play)
                    },
                    trailingIcon = {
                        PlayerMenuValue(
                            text = "${formatFadeShort(fadeInMs)} / ${formatFadeShort(fadeOutMs)}",
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        showFadeDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { PlayerMenuLabel("音浪联动") },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_chart)
                    },
                    trailingIcon = {
                        PlayerMenuValue(
                            text = if (audioReactiveOn) "开" else "关",
                            color = if (audioReactiveOn) {
                                Color.White.copy(alpha = 0.78f)
                            } else {
                                Color.White.copy(alpha = 0.42f)
                            }
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleAudioReactive()
                    }
                )
                DropdownMenuItem(
                    text = { PlayerMenuLabel("桌面歌词") },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_desktop_lyrics)
                    },
                    trailingIcon = {
                        PlayerMenuValue(
                            text = if (desktopLyricsEnabled) "开" else "关",
                            color = if (desktopLyricsEnabled) {
                                Color.White.copy(alpha = 0.78f)
                            } else {
                                Color.White.copy(alpha = 0.42f)
                            }
                        )
                    },
                    enabled = track != null,
                    onClick = {
                        menuExpanded = false
                        onToggleDesktopLyrics()
                    }
                )
                DropdownMenuItem(
                    text = {
                        PlayerMenuLabel(
                            if (isLandscape) "跟随系统方向" else "横屏沉浸模式"
                        )
                    },
                    leadingIcon = {
                        PlayerMenuIcon(R.drawable.ic_rotate)
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleOrientation()
                    }
                )
            }
        }
    }
    if (showSleepDialog) {
        SleepTimerDialog(
            remainingMs = sleepTimerRemainingMs,
            onDismiss = { showSleepDialog = false },
            onSet = { minutes ->
                showSleepDialog = false
                onSetSleepTimer(minutes)
            },
            onClear = {
                showSleepDialog = false
                onClearSleepTimer()
            }
        )
    }
    if (showFadeDialog) {
        PlaybackFadeDialog(
            fadeInMs = fadeInMs,
            fadeOutMs = fadeOutMs,
            onFadeInChange = onFadeInChange,
            onFadeOutChange = onFadeOutChange,
            onDismiss = { showFadeDialog = false }
        )
    }
}

@Composable
private fun CircleAction(
    iconRes: Int,
    description: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    subtle: Boolean = false
) {
    LiquidGlassSurface(
        modifier = Modifier
            .size(size)
            .bouncyClickable(pressScale = 0.88f, onClick = onClick),
        shape = RoundedCornerShape(50),
        tint = Color.White.copy(alpha = if (subtle) 0f else 0.02f),
        borderColors = listOf(
            Color.White.copy(alpha = 0.18f),
            Color.White.copy(alpha = 0.035f),
            Color.White.copy(alpha = 0.10f)
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = description,
                tint = Color.White.copy(alpha = if (subtle) 0.54f else 0.66f),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

private val PlayerMenuTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.62f),
    offset = Offset(0f, 1f),
    blurRadius = 7f
)

@Composable
private fun PlayerMenuLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(shadow = PlayerMenuTextShadow),
        color = Color.White.copy(alpha = 0.90f)
    )
}

@Composable
private fun PlayerMenuValue(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(shadow = PlayerMenuTextShadow),
        color = color
    )
}

@Composable
private fun PlayerMenuIcon(iconRes: Int) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.size(19.dp)
    )
}

private fun formatFadeShort(milliseconds: Int): String {
    if (milliseconds <= 0) return "关"
    val tenths = (milliseconds.coerceAtMost(2_000) / 100)
    return if (tenths % 10 == 0) {
        "${tenths / 10}s"
    } else {
        "${tenths / 10}.${tenths % 10}s"
    }
}

private fun formatFadeValue(milliseconds: Float): String {
    val safe = milliseconds.roundToInt().coerceIn(0, 2_000)
    if (safe == 0) return "关闭"
    val whole = safe / 1_000
    val tenth = (safe % 1_000) / 100
    return if (tenth == 0) "$whole 秒" else "$whole.$tenth 秒"
}

private fun snapFadeValue(value: Float): Float =
    ((value / 100f).roundToInt() * 100f).coerceIn(0f, 2_000f)

@Composable
private fun PlaybackFadeDialog(
    fadeInMs: Int,
    fadeOutMs: Int,
    onFadeInChange: (Int) -> Unit,
    onFadeOutChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var fadeInDraft by remember(fadeInMs) { mutableStateOf(fadeInMs.toFloat()) }
    var fadeOutDraft by remember(fadeOutMs) { mutableStateOf(fadeOutMs.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF17121D),
        titleContentColor = CatCream,
        textContentColor = CatTextSecondary,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                text = "播放渐入 / 渐出",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "控制 App 内播放和暂停时的音量过渡。设为 0 秒即关闭；调整结果会立即记住。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                FadeSliderRow(
                    label = "渐入",
                    value = fadeInDraft,
                    accent = CatPeach,
                    onValueChange = { fadeInDraft = snapFadeValue(it) },
                    onValueChangeFinished = {
                        onFadeInChange(fadeInDraft.roundToInt().coerceIn(0, 2_000))
                    }
                )
                Spacer(Modifier.height(14.dp))
                FadeSliderRow(
                    label = "渐出",
                    value = fadeOutDraft,
                    accent = CatPeach,
                    onValueChange = { fadeOutDraft = snapFadeValue(it) },
                    onValueChangeFinished = {
                        onFadeOutChange(fadeOutDraft.roundToInt().coerceIn(0, 2_000))
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = CatPeach)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    fadeInDraft = 500f
                    fadeOutDraft = 300f
                    onFadeInChange(500)
                    onFadeOutChange(300)
                }
            ) {
                Text("恢复默认", color = CatTextTertiary, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun FadeSliderRow(
    label: String,
    value: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            color = CatCream
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatFadeValue(value),
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = if (value <= 0f) CatTextTertiary else accent
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..2_000f,
        steps = 19,
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent.copy(alpha = 0.82f),
            inactiveTrackColor = Color.White.copy(alpha = 0.13f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "关闭",
            style = MaterialTheme.typography.labelSmall,
            color = CatTextTertiary.copy(alpha = 0.6f)
        )
        Text(
            text = "2 秒",
            style = MaterialTheme.typography.labelSmall,
            color = CatTextTertiary.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun SleepTimerDialog(
    remainingMs: Long,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
    onClear: () -> Unit
) {
    var customMinutes by rememberSaveable { mutableStateOf("") }
    val customValue = customMinutes.toIntOrNull()?.takeIf { it in 1..1440 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF17121D),
        titleContentColor = CatCream,
        textContentColor = CatTextSecondary,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                text = "定时暂停",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column {
                Text(
                    text = "到达时间前会柔和地淡出，然后自动暂停。计时在后台和锁屏时也会继续；除预设外也可以输入任意 1–1440 分钟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    lineHeight = 18.sp
                )
                if (remainingMs > 0L) {
                    Spacer(Modifier.height(13.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(CatPeach.copy(alpha = 0.10f))
                            .border(
                                1.dp,
                                CatPeach.copy(alpha = 0.16f),
                                RoundedCornerShape(15.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_timer),
                            contentDescription = null,
                            tint = CatPeach,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "正在倒计时",
                                style = MaterialTheme.typography.labelSmall,
                                color = CatTextTertiary
                            )
                            Text(
                                text = formatSleepTimerRemaining(remainingMs),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = CatPeach
                            )
                        }
                        TextButton(onClick = onClear) {
                            Text("关闭", color = CatTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val options = listOf(15, 30, 45, 60, 90)
                options.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { minutes ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .bouncyClickable(pressScale = 0.94f) { onSet(minutes) }
                                    .padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$minutes 分钟",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = CatCream
                                )
                            }
                        }
                        repeat(3 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "自定义时间",
                    style = MaterialTheme.typography.labelSmall,
                    color = CatTextTertiary
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = customMinutes,
                        onValueChange = { value ->
                            if (value.length <= 4 && value.all { it.isDigit() }) {
                                customMinutes = value
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = CatCream),
                        cursorBrush = SolidColor(CatPeach),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { customValue?.let(onSet) }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.10f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (customMinutes.isEmpty()) {
                                    Text(
                                        text = "输入分钟数，如 120",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CatTextTertiary.copy(alpha = 0.6f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        enabled = customValue != null,
                        onClick = { customValue?.let(onSet) }
                    ) {
                        Text(
                            text = "开始",
                            color = if (customValue != null) CatPeach else CatTextTertiary
                        )
                    }
                }
                if (customMinutes.isNotEmpty() && customValue == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "请输入 1–1440 之间的分钟数",
                        style = MaterialTheme.typography.labelSmall,
                        color = CatPeach.copy(alpha = 0.8f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = CatPeach)
            }
        }
    )
}

private fun formatSleepTimerRemaining(remainingMs: Long): String {
    val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0L) "$hours:$mm:$ss" else "$mm:$ss"
}

@Composable
private fun TrackMeta(
    track: Track?,
    formatLabel: String,
    accent: Color,
    centered: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val album = track?.album?.takeIf { it.isNotBlank() && it != "未知专辑" }
    val overline = buildString {
        if (album != null) {
            append(album)
            append(" · ")
        }
        append(formatLabel)
    }
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    val horizontal = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.48f),
        offset = Offset(0f, 2f),
        blurRadius = 18f
    )

    Column(
        modifier = modifier,
        horizontalAlignment = horizontal
    ) {
        Text(
            text = overline,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp
            ),
            color = accent.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = track?.title ?: "还没有正在播放的歌曲",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = if (compact) 26.sp else 29.sp,
                lineHeight = if (compact) 31.sp else 35.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                shadow = textShadow
            ),
            color = CatCream.copy(alpha = 0.96f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = track?.artist ?: "从音乐馆选择一首歌",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (compact) 13.sp else 14.sp,
                shadow = textShadow
            ),
            color = Color.White.copy(alpha = 0.48f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PlayerDock(
    trackId: String?,
    player: PlayerUiSnapshot,
    accent: Color,
    durationMs: Long,
    isLandscape: Boolean,
    showLyrics: Boolean,
    audioReactiveOn: Boolean,
    desktopLyricsEnabled: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleDesktopLyrics: () -> Unit,
    onToggleAudioReactive: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember(trackId) { mutableStateOf<Float?>(null) }
    val playbackFraction = if (durationMs > 0L) {
        (player.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = dragFraction ?: playbackFraction
    val shownPositionMs = if (dragFraction != null) {
        (shownFraction * durationMs).toLong()
    } else {
        player.positionMs
    }
    val shape = RoundedCornerShape(if (isLandscape) 25.dp else 28.dp)

    LiquidGlassSurface(
        modifier = modifier,
        shape = shape,
        tint = Color(0x220B0B11),
        borderColors = listOf(
            Color.White.copy(alpha = 0.24f),
            GlassBorder,
            CatPeach.copy(alpha = 0.12f)
        ),
        sensorSpecular = true,
        contentPadding = PaddingValues(
            horizontal = if (isLandscape) 16.dp else 13.dp,
            vertical = if (isLandscape) 7.dp else 9.dp
        )
    ) {
        Column(Modifier.fillMaxWidth()) {
        if (isLandscape) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 620.dp
                val playbackWidth = minOf(330.dp, maxWidth * 0.48f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockSeekRow(
                        isLandscape = true,
                        showLyrics = showLyrics,
                        audioReactiveOn = audioReactiveOn,
                        desktopLyricsEnabled = desktopLyricsEnabled,
                        accent = accent,
                        shownPositionMs = shownPositionMs,
                        durationMs = durationMs,
                        shownFraction = shownFraction,
                        isScrubbing = dragFraction != null,
                        onToggleLyrics = onToggleLyrics,
                        onToggleDesktopLyrics = onToggleDesktopLyrics,
                        onToggleAudioReactive = onToggleAudioReactive,
                        onScrub = { fraction -> dragFraction = fraction },
                        onFinishScrub = {
                            dragFraction?.let { fraction ->
                                if (durationMs > 0L) {
                                    onSeek((fraction * durationMs).toLong())
                                }
                            }
                            dragFraction = null
                        },
                        onCancelScrub = { dragFraction = null },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(if (narrow) 10.dp else 18.dp))
                    PlaybackControls(
                        player = player,
                        accent = accent,
                        compact = true,
                        onToggle = onToggle,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        modifier = Modifier.width(playbackWidth)
                    )
                }
            }
        } else {
            DockSeekRow(
                isLandscape = false,
                showLyrics = showLyrics,
                audioReactiveOn = audioReactiveOn,
                desktopLyricsEnabled = desktopLyricsEnabled,
                accent = accent,
                shownPositionMs = shownPositionMs,
                durationMs = durationMs,
                shownFraction = shownFraction,
                isScrubbing = dragFraction != null,
                onToggleLyrics = onToggleLyrics,
                onToggleDesktopLyrics = onToggleDesktopLyrics,
                onToggleAudioReactive = onToggleAudioReactive,
                onScrub = { fraction -> dragFraction = fraction },
                onFinishScrub = {
                    dragFraction?.let { fraction ->
                        if (durationMs > 0L) {
                            onSeek((fraction * durationMs).toLong())
                        }
                    }
                    dragFraction = null
                },
                onCancelScrub = { dragFraction = null },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(3.dp))

            PlaybackControls(
                player = player,
                accent = accent,
                compact = false,
                onToggle = onToggle,
                onNext = onNext,
                onPrevious = onPrevious,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat
            )
        }
    }
    }
}

@Composable
private fun DockSeekRow(
    isLandscape: Boolean,
    showLyrics: Boolean,
    audioReactiveOn: Boolean,
    desktopLyricsEnabled: Boolean,
    accent: Color,
    shownPositionMs: Long,
    durationMs: Long,
    shownFraction: Float,
    isScrubbing: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleDesktopLyrics: () -> Unit,
    onToggleAudioReactive: () -> Unit,
    onScrub: (Float) -> Unit,
    onFinishScrub: () -> Unit,
    onCancelScrub: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockAction(
            iconRes = R.drawable.ic_lyrics,
            label = "词",
            description = if (showLyrics) "收起歌词" else "显示歌词",
            active = showLyrics,
            accent = accent,
            onClick = onToggleLyrics
        )
        Spacer(Modifier.width(1.dp))
        DockAction(
            label = "浮",
            description = if (desktopLyricsEnabled) "关闭桌面歌词" else "开启桌面歌词",
            active = desktopLyricsEnabled,
            accent = accent,
            onClick = onToggleDesktopLyrics
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = formatClock(shownPositionMs),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium
            ),
            color = Color.White.copy(alpha = 0.42f)
        )
        Spacer(Modifier.width(7.dp))
        ThinSeekBar(
            fraction = shownFraction,
            accent = accent,
            isScrubbing = isScrubbing,
            onScrub = onScrub,
            onFinishScrub = onFinishScrub,
            onCancelScrub = onCancelScrub,
            modifier = Modifier
                .weight(1f)
                .height(if (isLandscape) 22.dp else 26.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (durationMs > 0L) formatSongDuration(durationMs) else "--:--",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium
            ),
            color = Color.White.copy(alpha = 0.42f)
        )
        Spacer(Modifier.width(5.dp))
        DockAction(
            iconRes = R.drawable.ic_chart,
            description = if (audioReactiveOn) "关闭音浪联动" else "开启音浪联动",
            active = audioReactiveOn,
            accent = accent,
            onClick = onToggleAudioReactive
        )
    }
}

@Composable
private fun ThinSeekBar(
    fraction: Float,
    accent: Color,
    isScrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onFinishScrub: () -> Unit,
    onCancelScrub: () -> Unit,
    modifier: Modifier = Modifier,
    minimal: Boolean = false
) {
    Canvas(
        modifier = modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val centerY = size.height / 2f
                    val lineSlop = 9.dp.toPx()
                    val dragSlop = viewConfiguration.touchSlop.coerceAtLeast(7f)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val nearLine = abs(down.position.y - centerY) <= lineSlop
                    var scrubbing = false
                    var completed = false
                    fun fractionAt(x: Float) =
                        (x / width).coerceIn(0f, 1f)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.pressed) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                // A deliberate horizontal drag arms the seek.
                                // Stray taps in the row and vertical swipes do
                                // not change the playback position.
                                if (!scrubbing &&
                                    abs(dx) > dragSlop &&
                                    abs(dx) >= abs(dy)
                                ) {
                                    scrubbing = true
                                }
                                if (scrubbing) {
                                    onScrub(fractionAt(change.position.x))
                                    change.consume()
                                }
                            } else {
                                val tapOnLine = nearLine &&
                                    !scrubbing &&
                                    abs(change.position.x - down.position.x) <= lineSlop &&
                                    abs(change.position.y - down.position.y) <= lineSlop
                                if (scrubbing || tapOnLine) {
                                    onScrub(fractionAt(change.position.x))
                                    onFinishScrub()
                                    change.consume()
                                }
                                completed = true
                                break
                            }
                        }
                    } finally {
                        if (!completed && scrubbing) onCancelScrub()
                    }
                }
            }
    ) {
        val centerY = size.height / 2f
        val safeFraction = fraction.coerceIn(0f, 1f)
        if (minimal) {
            val trackHeight = 1.1.dp.toPx()
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner
            )
            if (safeFraction > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.42f),
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(size.width * safeFraction, trackHeight),
                    cornerRadius = corner
                )
            }
            if (isScrubbing) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.82f),
                    radius = 2.2.dp.toPx(),
                    center = Offset(size.width * safeFraction, centerY)
                )
            }
        } else {
            val trackHeight = 2.dp.toPx()
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.15f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner
            )
            if (safeFraction > 0f) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.92f),
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(size.width * safeFraction, trackHeight),
                    cornerRadius = corner
                )
            }
            val thumbCenter = Offset(size.width * safeFraction, centerY)
            val thumbRadius = if (isScrubbing) 4.4.dp.toPx() else 3.2.dp.toPx()
            drawCircle(
                color = accent.copy(alpha = 0.22f),
                radius = thumbRadius * 2.8f,
                center = thumbCenter
            )
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = thumbCenter
            )
        }
    }
}

@Composable
private fun DockAction(
    iconRes: Int? = null,
    label: String? = null,
    description: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val tint = if (active) accent else Color.White.copy(alpha = 0.52f)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
            .semantics { contentDescription = description }
            .bouncyClickable(pressScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = tint
            )
        } else if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    player: PlayerUiSnapshot,
    accent: Color,
    compact: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(if (compact) 0.76f else 1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlIcon(
                iconRes = R.drawable.ic_shuffle,
                description = "随机播放",
                active = player.shuffleEnabled,
                accent = accent,
                iconSize = if (compact) 18.dp else 20.dp,
                containerSize = if (compact) 38.dp else 42.dp,
                onClick = onToggleShuffle
            )
            ControlIcon(
                iconRes = R.drawable.ic_skip_previous,
                description = "上一首",
                accent = accent,
                iconSize = if (compact) 22.dp else 24.dp,
                containerSize = if (compact) 38.dp else 42.dp,
                enabled = player.hasPrevious || player.positionMs > 4_000L,
                onClick = onPrevious
            )
            PlayPauseButton(
                isPlaying = player.isPlaying,
                accent = accent,
                compact = compact,
                onClick = onToggle
            )
            ControlIcon(
                iconRes = R.drawable.ic_skip_next,
                description = "下一首",
                accent = accent,
                iconSize = if (compact) 22.dp else 24.dp,
                containerSize = if (compact) 38.dp else 42.dp,
                enabled = player.hasNext,
                onClick = onNext
            )
            RepeatIcon(
                repeatMode = player.repeatMode,
                accent = accent,
                iconSize = if (compact) 18.dp else 20.dp,
                containerSize = if (compact) 38.dp else 42.dp,
                onClick = onCycleRepeat
            )
        }
    }
}

@Composable
private fun ControlIcon(
    iconRes: Int,
    description: String,
    accent: Color,
    iconSize: Dp,
    containerSize: Dp,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.22f)
        active -> accent
        else -> Color.White.copy(alpha = 0.62f)
    }
    Box(
        modifier = Modifier
            .size(containerSize)
            .bouncyClickable(enabled = enabled, pressScale = 0.86f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-3).dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

@Composable
private fun RepeatIcon(
    repeatMode: Int,
    accent: Color,
    iconSize: Dp,
    containerSize: Dp,
    onClick: () -> Unit
) {
    val active = repeatMode != Player.REPEAT_MODE_OFF
    val label = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> "单曲"
        Player.REPEAT_MODE_ALL -> "列表"
        else -> ""
    }
    Column(
        modifier = Modifier
            .size(containerSize)
            .bouncyClickable(pressScale = 0.86f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_repeat),
                contentDescription = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "单曲循环"
                    Player.REPEAT_MODE_ALL -> "列表循环"
                    else -> "循环已关闭，点击开启列表循环"
                },
                tint = if (active) accent else Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(iconSize)
            )
            if (repeatMode == Player.REPEAT_MODE_ONE) {
                Text(
                    text = "1",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.sp,
                        lineHeight = 7.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-3).dp)
                )
            }
        }
        Text(
            text = label.ifEmpty { "循环" },
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 7.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Medium
            ),
            color = if (active) accent else CatTextTertiary.copy(alpha = 0.5f),
            maxLines = 1
        )
    }
}

@Composable
private fun rememberArtworkAccent(track: Track?, fallback: Color): Color {
    val context = LocalContext.current
    val artworkFile = track?.artworkFile
    val extracted by produceState(
        initialValue = fallback,
        key1 = artworkFile,
        key2 = fallback
    ) {
        if (artworkFile.isNullOrBlank()) {
            value = fallback
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                extractArtworkAccent(File(context.filesDir, "artwork/$artworkFile"))
            }.getOrNull() ?: fallback
        }
    }
    return extracted
}

/**
 * Samples the cover at a small size and turns its most characteristic colors
 * into a soft, readable highlight. This keeps controls and the glow tied to
 * the artwork without shipping a full palette library.
 */
private fun extractArtworkAccent(file: File): Color? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 64 &&
        bounds.outHeight / (sample * 2) >= 64
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        pixels.forEach { pixel ->
            if (AndroidColor.alpha(pixel) < 128) return@forEach
            val r = AndroidColor.red(pixel)
            val g = AndroidColor.green(pixel)
            val b = AndroidColor.blue(pixel)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val value = max / 255f
            if (value < 0.08f) return@forEach
            val saturation = if (max == 0) 0f else (max - min) / max.toFloat()
            val pixelWeight = (0.12f + saturation * 0.88f) * (0.30f + value)
            red += r * pixelWeight
            green += g * pixelWeight
            blue += b * pixelWeight
            weight += pixelWeight
        }
        if (weight <= 0.0) return null
        val r = (red / weight).roundToInt().coerceIn(0, 255)
        val g = (green / weight).roundToInt().coerceIn(0, 255)
        val b = (blue / weight).roundToInt().coerceIn(0, 255)
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(r, g, b, hsv)
        if (hsv[1] < 0.065f) {
            Color(0xFFE4D6C4)
        } else {
            hsv[1] = hsv[1].coerceIn(0.34f, 0.68f)
            hsv[2] = hsv[2].coerceIn(0.76f, 0.94f)
            Color(AndroidColor.HSVToColor(hsv))
        }
    } finally {
        bitmap.recycle()
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    accent: Color,
    compact: Boolean,
    onClick: () -> Unit
) {
    val size = if (compact) 46.dp else 52.dp
    val iconSize = if (compact) 20.dp else 22.dp
    Box(
        modifier = Modifier.size(size + 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isPlaying) {
            Box(
                Modifier
                    .size(size + 8.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.10f), Color.Transparent)
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.045f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                .bouncyClickable(pressScale = 0.90f, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White.copy(alpha = 0.94f),
                modifier = Modifier
                    .size(iconSize)
                    .padding(start = if (isPlaying) 0.dp else 2.dp)
            )
        }
    }
}
