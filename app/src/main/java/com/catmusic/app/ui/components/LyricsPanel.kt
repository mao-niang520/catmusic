package com.catmusic.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmusic.app.R
import com.catmusic.app.data.Track
import com.catmusic.app.playback.AudioReactiveSignal
import com.catmusic.app.util.LrcParser
import com.catmusic.app.util.ParsedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp
import kotlin.math.max

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
    onSeek: (Long) -> Unit,
    onImportLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lyricsKey = track?.lyricsFile

    // Keep the local PCM analyser alive while this native lyric stage is on
    // screen, even if the optional 音浪 visual mode is off.
    DisposableEffect(Unit) {
        AudioReactiveSignal.uiRequested = true
        onDispose { AudioReactiveSignal.uiRequested = false }
    }

    var state by remember(track?.id, lyricsKey) {
        mutableStateOf<LyricsState>(
            if (lyricsKey.isNullOrBlank()) LyricsState.Missing else LyricsState.Loading
        )
    }

    LaunchedEffect(track?.id, lyricsKey) {
        if (lyricsKey.isNullOrBlank()) {
            state = LyricsState.Missing
            return@LaunchedEffect
        }
        state = LyricsState.Loading
        val file = File(context.filesDir, "lyrics/$lyricsKey")
        state = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching LyricsState.Missing
                val parsed = LrcParser.parse(file.readText())
                if (parsed.lines.isEmpty()) LyricsState.Missing else LyricsState.Ready(parsed)
            }.getOrElse { LyricsState.Error }
        }
    }

    val ready = state as? LyricsState.Ready
    val activeIndex by remember(ready, offsetMs) {
        derivedStateOf {
            val lyricPosition = (player.positionMs - offsetMs).coerceAtLeast(0L)
            ready?.parsed?.activeIndex(lyricPosition) ?: -1
        }
    }

    // The native lyric stage gets the same visible kick response as the
    // WebView stage. Beat events and low-band transients both feed a short
    // envelope; the value is read inside graphicsLayer so the pulse animates
    // without recomposing the lyric list every frame.
    val beatPulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var envelope = 0f
        var bassBaseline = 0.06f
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
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
                        offsetMs = offsetMs,
                        beatPulse = beatPulse,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlainLyrics(
                        parsed = current.parsed,
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
    offsetMs: Long,
    beatPulse: State<Float>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val centerIndex = activeIndex.coerceAtLeast(0)
    val denseWindow = isLandscape && parsed.lines.any { !it.translation.isNullOrBlank() }
    AnimatedContent(
        targetState = centerIndex,
        transitionSpec = {
            (fadeIn(tween(340)) + slideInVertically(tween(360)) { it / 10 })
                .togetherWith(
                    fadeOut(tween(220)) + slideOutVertically(tween(260)) { -it / 14 }
                )
        },
        label = "immersiveLyrics",
        modifier = modifier
    ) { index ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (isLandscape) 56.dp else 28.dp,
                    end = if (isLandscape) 56.dp else 28.dp,
                    bottom = when {
                        denseWindow -> 0.dp
                        isLandscape -> 48.dp
                        else -> 36.dp
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val offsets = if (denseWindow) -2..1 else -2..2
            for (offset in offsets) {
                val lineIndex = index + offset
                val line = parsed.lines.getOrNull(lineIndex) ?: continue
                val isActive = lineIndex == activeIndex && activeIndex >= 0
                LyricPair(
                    line = line,
                    offset = offset,
                    isActive = isActive,
                    isLandscape = isLandscape,
                    dense = denseWindow,
                    beatPulse = beatPulse,
                    onClick = if (parsed.hasTiming) {
                        { onSeek((line.timeMs + offsetMs).coerceAtLeast(0L)) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun LyricPair(
    line: com.catmusic.app.util.LyricLine,
    offset: Int,
    isActive: Boolean,
    isLandscape: Boolean,
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
        isActive && isLandscape -> 29.sp
        isActive -> 32.sp
        isLandscape -> 21.sp
        else -> 24.sp
    }
    val translationSize = when {
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
                    dense && isActive -> 4.dp
                    dense -> 3.dp
                    isActive && isLandscape -> 7.dp
                    isActive -> 11.dp
                    isLandscape -> 4.dp
                    else -> 7.dp
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = originalSize,
                lineHeight = (originalSize.value * 1.28f).sp,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center,
                shadow = if (isActive) activeGlow else null
            ),
            color = originalColor,
            textAlign = TextAlign.Center,
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
                    textAlign = TextAlign.Center
                ),
                color = translationColor,
                textAlign = TextAlign.Center,
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
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 28.dp,
            end = 28.dp,
            top = 90.dp,
            bottom = 90.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(parsed.lines) { line ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                ),
                color = Color(0xFFE9DEE4).copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
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
