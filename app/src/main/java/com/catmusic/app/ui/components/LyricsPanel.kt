package com.catmusic.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmusic.app.R
import com.catmusic.app.data.AdaptiveMotion
import com.catmusic.app.data.LyricDisplayLoader
import com.catmusic.app.data.LyricTranslationMode
import com.catmusic.app.data.Track
import com.catmusic.app.playback.AudioReactiveSignal
import com.catmusic.app.util.ParsedLyrics
import com.catmusic.app.util.formatClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object Missing : LyricsState
    data object Error : LyricsState
    data class Ready(val parsed: ParsedLyrics) : LyricsState
}

/**
 * The immersive lyric layer. It deliberately draws no card, no header and no
 * album art: the parent supplies a full-bleed gradient/sound field and this
 * layer keeps the current bilingual lyric floating in the middle of it.
 */
@Composable
fun ImmersiveLyrics(
    track: Track?,
    player: PlayerUiSnapshot,
    accent: Color,
    isLandscape: Boolean,
    offsetMs: Long,
    translationMode: LyricTranslationMode,
    onSeek: (Long) -> Unit,
    onImportLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    leftAligned: Boolean = false
) {
    val context = LocalContext.current
    val lyricsKey = track?.lyricsFile

    // Keep the local PCM analyser alive while this native lyric stage is on
    // screen, even if the optional 音浪 visual mode is off.
    DisposableEffect(Unit) {
        AudioReactiveSignal.uiRequested = true
        onDispose { AudioReactiveSignal.uiRequested = false }
    }

    var state by remember(track?.id, lyricsKey, translationMode) {
        mutableStateOf<LyricsState>(
            if (lyricsKey.isNullOrBlank()) LyricsState.Missing else LyricsState.Loading
        )
    }

    LaunchedEffect(track?.id, lyricsKey, translationMode) {
        if (lyricsKey.isNullOrBlank()) {
            state = LyricsState.Missing
            return@LaunchedEffect
        }
        state = LyricsState.Loading
        val base = LyricDisplayLoader.load(context, track, translationMode)
        if (base == null || base.lines.isEmpty()) {
            state = LyricsState.Missing
            return@LaunchedEffect
        }
        // Show the local document immediately; online completion can arrive a
        // moment later without holding the whole lyric page on a spinner.
        state = LyricsState.Ready(base)
        if (translationMode == LyricTranslationMode.ONLINE) {
            val enriched = LyricDisplayLoader.enrich(context, track, base)
            if (enriched !== base) state = LyricsState.Ready(enriched)
        }
    }

    val ready = state as? LyricsState.Ready
    val activeIndex by remember(ready, offsetMs) {
        derivedStateOf {
            val lyricPosition = (player.positionMs - offsetMs).coerceAtLeast(0L)
            ready?.parsed?.activeIndex(lyricPosition) ?: -1
        }
    }
    val frameRate = LocalMotionFrameRate.current

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) AdaptiveMotion.pulse()
    }

    // The native lyric stage gets the same visible kick response as the
    // WebView stage. Beat events and low-band transients both feed a short
    // envelope. The loop follows the selected motion budget instead of waking
    // the screen at its full panel rate forever.
    val beatPulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(frameRate, player.isPlaying) {
        var envelope = 0f
        var bassBaseline = 0.06f
        var lastNanos = 0L
        while (true) {
            delay(AdaptiveMotion.frameDelayMillis(frameRate, player.isPlaying))
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) {
                0.016f
            } else {
                ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            lastNanos = now
            val energy = AudioReactiveSignal.current
            val active = energy.active
            val bass = if (active) energy.bass else 0f
            val beat = if (active) energy.beat else 0f
            val level = if (active) energy.level else 0f
            bassBaseline += (bass - bassBaseline) *
                (if (bass > bassBaseline) 0.08f else 0.015f)
            val transient = ((bass - bassBaseline) * 4f).coerceAtLeast(0f)
            val punch = (
                transient + beat * 0.9f + bass * 0.2f + level * 0.08f
                ).coerceIn(0f, 1.2f)
            envelope = max(
                punch,
                envelope * exp((-dt * 5.5f).toDouble()).toFloat()
            )
            beatPulse.floatValue = envelope.coerceIn(0f, 1f)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val current = state) {
            LyricsState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = accent,
                strokeWidth = 2.dp
            )

            LyricsState.Missing -> LyricsPlaceholder(
                title = "还没有歌词",
                description = "导入 LRC 或 TXT 歌词，让文字浮在音乐里。",
                actionText = "导入歌词",
                accent = accent,
                onAction = onImportLyrics,
                modifier = Modifier.fillMaxSize()
            )

            LyricsState.Error -> LyricsPlaceholder(
                title = "歌词读取失败",
                description = "请选择 UTF-8、UTF-16 或 GBK 编码的 LRC / TXT 文件。",
                actionText = "重新选择",
                accent = accent,
                onAction = onImportLyrics,
                modifier = Modifier.fillMaxSize()
            )

            is LyricsState.Ready -> {
                if (current.parsed.hasTiming) {
                    TimedLyrics(
                        parsed = current.parsed,
                        activeIndex = activeIndex,
                        isLandscape = isLandscape,
                        leftAligned = leftAligned,
                        offsetMs = offsetMs,
                        beatPulse = beatPulse,
                        accent = accent,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlainLyrics(
                        parsed = current.parsed,
                        leftAligned = leftAligned,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun TimedLyrics(
    parsed: ParsedLyrics,
    activeIndex: Int,
    isLandscape: Boolean,
    leftAligned: Boolean,
    offsetMs: Long,
    beatPulse: State<Float>,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val denseWindow = isLandscape && parsed.lines.any { !it.translation.isNullOrBlank() }
    val maxIndex = parsed.lines.lastIndex
    var selectedIndex by remember(parsed) { mutableStateOf<Int?>(null) }
    var isDragging by remember(parsed) { mutableStateOf(false) }
    var isTouching by remember(parsed) { mutableStateOf(false) }
    val latestSelected = rememberUpdatedState(selectedIndex)
    val latestActive = rememberUpdatedState(activeIndex.coerceIn(0, maxIndex))
    val latestOffset = rememberUpdatedState(offsetMs)
    val latestOnSeek = rememberUpdatedState(onSeek)

    LaunchedEffect(maxIndex) {
        selectedIndex = selectedIndex?.coerceIn(0, maxIndex)
    }

    // Previewing a lyric never changes playback. A tap commits the preview;
    // otherwise the live line returns after three seconds. Touch-down pauses
    // the countdown so a late tap still lands.
    LaunchedEffect(selectedIndex, isDragging, isTouching) {
        val selected = selectedIndex ?: return@LaunchedEffect
        if (!isDragging && !isTouching) {
            delay(3_000L)
            if (selectedIndex == selected) selectedIndex = null
        }
    }

    val seekModifier = if (parsed.hasTiming && maxIndex >= 0) {
        Modifier.pointerInput(parsed, isLandscape) {
            val lineStep = (if (isLandscape) 44.dp else 55.dp)
                .toPx()
                .coerceAtLeast(1f)
            val tapSlop = viewConfiguration.touchSlop.coerceAtLeast(6f)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                isTouching = true
                val startIndex = (latestSelected.value ?: latestActive.value)
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
                                selectedIndex = startIndex
                            }
                            if (dragging) {
                                val steps = (-dy / lineStep).roundToInt()
                                val target = (startIndex + steps).coerceIn(0, maxIndex)
                                if (selectedIndex != target) selectedIndex = target
                                change.consume()
                            }
                        } else {
                            if (dragging) {
                                change.consume()
                            } else {
                                val stayed =
                                    abs(change.position.x - down.position.x) <= tapSlop &&
                                        abs(change.position.y - down.position.y) <= tapSlop
                                val chosen = latestSelected.value
                                if (stayed && chosen != null) {
                                    parsed.lines.getOrNull(chosen)?.let { line ->
                                        latestOnSeek.value(
                                            (line.timeMs + latestOffset.value)
                                                .coerceAtLeast(0L)
                                        )
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

    val displayIndex = selectedIndex?.coerceIn(0, maxIndex)
    val targetTimeMs = displayIndex?.let { index ->
        parsed.lines.getOrNull(index)?.let { line ->
            (line.timeMs + offsetMs).coerceAtLeast(0L)
        }
    }

    Box(modifier.then(seekModifier)) {
        if (displayIndex != null) {
            TimedLyricsWindow(
                parsed = parsed,
                centerIndex = displayIndex,
                activeIndex = displayIndex,
                isLandscape = isLandscape,
                leftAligned = leftAligned,
                denseWindow = denseWindow,
                beatPulse = beatPulse,
                onSeekLine = null,
                modifier = Modifier.fillMaxSize()
            )
            if (targetTimeMs != null) {
                val hudShape = RoundedCornerShape(50)
                Row(
                    modifier = Modifier
                        .align(
                            if (leftAligned) Alignment.TopStart
                            else Alignment.TopCenter
                        )
                        .padding(top = if (isLandscape) 2.dp else 8.dp)
                        .clip(hudShape)
                        .background(Color.Black.copy(alpha = 0.26f))
                        .border(1.dp, Color.White.copy(alpha = 0.07f), hudShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatClock(targetTimeMs),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = if (isLandscape) 14.sp else 16.sp,
                            fontWeight = FontWeight.SemiBold
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
                            fontSize = if (isLandscape) 9.sp else 10.sp
                        ),
                        color = Color.White.copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isDragging) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "3 秒后返回",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = if (isLandscape) 8.5.sp else 9.5.sp
                            ),
                            color = Color.White.copy(alpha = 0.30f),
                            maxLines = 1
                        )
                    }
                }
            }
        } else {
            val centerIndex = activeIndex.coerceAtLeast(0)
            AnimatedContent(
                targetState = centerIndex,
                transitionSpec = {
                    (fadeIn(tween(340)) + slideInVertically(tween(360)) { it / 10 })
                        .togetherWith(
                            fadeOut(tween(220)) + slideOutVertically(tween(260)) { -it / 14 }
                        )
                },
                label = "immersiveLyrics",
                modifier = Modifier.fillMaxSize()
            ) { index ->
                TimedLyricsWindow(
                    parsed = parsed,
                    centerIndex = index,
                    activeIndex = activeIndex,
                    isLandscape = isLandscape,
                    leftAligned = leftAligned,
                    denseWindow = denseWindow,
                    beatPulse = beatPulse,
                    onSeekLine = if (parsed.hasTiming) {
                        { line ->
                            onSeek((line.timeMs + offsetMs).coerceAtLeast(0L))
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun TimedLyricsWindow(
    parsed: ParsedLyrics,
    centerIndex: Int,
    activeIndex: Int,
    isLandscape: Boolean,
    leftAligned: Boolean,
    denseWindow: Boolean,
    beatPulse: State<Float>,
    onSeekLine: ((com.catmusic.app.util.LyricLine) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(
            start = when {
                leftAligned -> 0.dp
                isLandscape -> 56.dp
                else -> 28.dp
            },
            end = when {
                leftAligned -> 0.dp
                isLandscape -> 56.dp
                else -> 28.dp
            },
            top = if (leftAligned) 2.dp else 0.dp,
            bottom = when {
                leftAligned -> 4.dp
                denseWindow -> 0.dp
                isLandscape -> 48.dp
                else -> 36.dp
            }
        ),
        horizontalAlignment = if (leftAligned) {
            Alignment.Start
        } else {
            Alignment.CenterHorizontally
        },
        verticalArrangement = Arrangement.Center
    ) {
        val offsets = if (denseWindow) -2..1 else -2..2
        for (offset in offsets) {
            val lineIndex = centerIndex + offset
            val line = parsed.lines.getOrNull(lineIndex) ?: continue
            val isActive = lineIndex == activeIndex && activeIndex >= 0
            LyricPair(
                line = line,
                offset = offset,
                isActive = isActive,
                isLandscape = isLandscape,
                leftAligned = leftAligned,
                dense = denseWindow,
                beatPulse = beatPulse,
                onClick = if (onSeekLine != null) {
                    { onSeekLine(line) }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun LyricPair(
    line: com.catmusic.app.util.LyricLine,
    offset: Int,
    isActive: Boolean,
    isLandscape: Boolean,
    leftAligned: Boolean,
    dense: Boolean,
    beatPulse: State<Float>,
    onClick: (() -> Unit)?
) {
    val distance = kotlin.math.abs(offset)
    val baseAlpha = when (distance) {
        0 -> 1f
        1 -> 0.46f
        else -> 0.30f
    }
    val originalSize = when {
        leftAligned && isActive -> 24.sp
        leftAligned -> 16.sp
        isActive && isLandscape -> 29.sp
        isActive -> 32.sp
        isLandscape -> 21.sp
        else -> 24.sp
    }
    val translationSize = when {
        leftAligned && isActive -> 12.5.sp
        leftAligned -> 10.5.sp
        isActive && isLandscape -> 14.5.sp
        isActive -> 16.sp
        isLandscape -> 11.5.sp
        else -> 13.5.sp
    }
    val activeColor = Color(0xFFFFFAF6)
    val inactiveColor = Color(0xFFE2C7D2)
    val originalColor = if (isActive) activeColor else inactiveColor.copy(alpha = baseAlpha)
    val translationColor = if (isActive) {
        Color(0xFFEDE0E6).copy(alpha = 0.84f)
    } else {
        Color(0xFFD6C1CB).copy(alpha = baseAlpha * 0.70f)
    }
    val activeGlow = Shadow(
        color = Color.White.copy(alpha = 0.40f),
        offset = Offset.Zero,
        blurRadius = 30f
    )
    val translation = line.translation?.takeIf { it.isNotBlank() }
    val clickModifier = if (onClick != null) {
        Modifier.bouncyClickable(pressScale = 0.985f, onClick = onClick)
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (isActive) {
                    val pulse = beatPulse.value.coerceIn(0f, 1f)
                    scaleX = 1f + pulse * 0.11f
                    scaleY = scaleX
                }
            }
            .then(clickModifier)
            .padding(
                vertical = when {
                    leftAligned && isActive -> 4.dp
                    leftAligned -> 3.dp
                    dense && isActive -> 4.dp
                    dense -> 3.dp
                    isActive && isLandscape -> 7.dp
                    isActive -> 11.dp
                    isLandscape -> 4.dp
                    else -> 7.dp
                }
            ),
        horizontalAlignment = if (leftAligned) {
            Alignment.Start
        } else {
            Alignment.CenterHorizontally
        }
    ) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = originalSize,
                lineHeight = (originalSize.value * 1.28f).sp,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center,
                shadow = if (isActive) activeGlow else null
            ),
            color = originalColor,
            textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        if (translation != null) {
            Spacer(Modifier.height(if (isActive) 5.dp else 3.dp))
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = translationSize,
                    lineHeight = (translationSize.value * 1.35f).sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center
                ),
                color = translationColor,
                textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PlainLyrics(
    parsed: ParsedLyrics,
    leftAligned: Boolean,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = if (leftAligned) {
            Alignment.Start
        } else {
            Alignment.CenterHorizontally
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = if (leftAligned) 0.dp else 28.dp,
            end = if (leftAligned) 0.dp else 28.dp,
            top = if (leftAligned) 4.dp else 90.dp,
            bottom = if (leftAligned) 4.dp else 90.dp
        ),
        verticalArrangement = Arrangement.spacedBy(if (leftAligned) 9.dp else 14.dp)
    ) {
        items(parsed.lines) { line ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (leftAligned) {
                    Alignment.Start
                } else {
                    Alignment.CenterHorizontally
                }
            ) {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = if (leftAligned) 16.sp else 22.sp,
                        lineHeight = if (leftAligned) 22.sp else 30.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center
                    ),
                    color = Color(0xFFE9DEE4).copy(alpha = 0.72f),
                    textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!line.translation.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = line.translation,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = if (leftAligned) 10.5.sp else 13.5.sp,
                            lineHeight = if (leftAligned) 14.sp else 18.sp,
                            textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center
                        ),
                        color = Color(0xFFD6C1CB).copy(alpha = 0.46f),
                        textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsPlaceholder(
    title: String,
    description: String,
    actionText: String,
    accent: Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lyrics),
            contentDescription = null,
            tint = accent.copy(alpha = 0.68f),
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            ),
            color = Color(0xFFF3EAEC)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            ),
            color = Color(0xFFD9C8CE).copy(alpha = 0.52f)
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.055f))
                .bouncyClickable(pressScale = 0.94f, onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.66f)
            )
        }
    }
}
