package com.catmusic.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catmusic.app.R
import com.catmusic.app.data.ListeningStats
import com.catmusic.app.data.Track
import com.catmusic.app.data.TrackStats
import com.catmusic.app.ui.components.CatMascot
import com.catmusic.app.ui.components.CoverArt
import com.catmusic.app.ui.components.EqualizerBars
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.GradientCtaButton
import com.catmusic.app.ui.components.GradientTitle
import com.catmusic.app.ui.components.PlayerUiSnapshot
import com.catmusic.app.ui.components.SectionHeader
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.ui.theme.GlassBorder
import com.catmusic.app.ui.theme.GlassBorderBright
import com.catmusic.app.util.dayKey
import com.catmusic.app.util.formatCompactDuration
import com.catmusic.app.util.formatListenCount
import com.catmusic.app.util.formatSongDuration
import com.catmusic.app.util.weekdayShort
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    tracks: List<Track>,
    statsFlow: StateFlow<ListeningStats>,
    player: PlayerUiSnapshot,
    onTrackAction: (Track) -> Unit,
    onShowImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by statsFlow.collectAsStateWithLifecycle()
    val currentTrackId = player.currentMediaId
    val isPlaying = player.isPlaying
    val today = LocalDate.now()
    val libraryDuration = remember(tracks) { tracks.sumOf { it.durationMs } }
    val totalPlays = remember(stats) { stats.tracks.values.sumOf { it.playCount } }
    val rankedTracks = remember(tracks, stats) {
        tracks.map { track -> track to (stats.tracks[track.id] ?: TrackStats()) }
            .sortedWith(
                compareByDescending<Pair<Track, TrackStats>> { it.second.listenedMs }
                    .thenByDescending { it.second.playCount }
                    .thenBy { it.first.title.lowercase(Locale.getDefault()) }
            )
    }
    val statsAccent = rankedTracks.firstOrNull()
        ?.takeIf { it.second.listenedMs > 0L }
        ?.let { coverPalette(it.first.id).last() }
        ?: CatMint
    val todayMs = stats.dailyMs[dayKey(today)] ?: 0L

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = "LISTENING LOG",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = CatViolet
                )
                Spacer(Modifier.height(8.dp))
                GradientTitle(
                    text = "聆听足迹",
                    style = MaterialTheme.typography.headlineLarge,
                    colors = listOf(CatCream, CatViolet, CatPeach)
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "每一秒的陪伴，都算数。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatTextSecondary
                )
            }
        }

        if (tracks.isEmpty()) {
            item(key = "empty") {
                StatsEmptyState(onShowImport = onShowImport)
            }
        } else {
            item(key = "hero") {
                TotalListeningHero(
                    totalMs = stats.totalMs,
                    todayMs = todayMs,
                    accent = statsAccent,
                    isPlaying = isPlaying
                )
            }
            item(key = "metrics") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        label = "曲目",
                        value = "${tracks.size} 首",
                        accent = CatViolet,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "播放",
                        value = formatListenCount(totalPlays),
                        accent = CatPeach,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "曲库",
                        value = conciseDuration(libraryDuration),
                        accent = CatMint,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item(key = "weekly") {
                WeeklyChart(
                    dailyMs = stats.dailyMs,
                    accent = statsAccent
                )
            }
            item(key = "track-title") {
                SectionHeader(
                    title = "单曲聆听",
                    subtitle = if (stats.totalMs == 0L) {
                        "开始播放后，这里会记录每一首歌的陪伴"
                    } else {
                        "按实际聆听时长排序 · 共 ${tracks.size} 首"
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    trailing = {
                        TinyLabel(
                            text = "累计 ${formatCompactDuration(stats.totalMs)}",
                            color = CatTextSecondary,
                            background = Color.White.copy(alpha = 0.05f)
                        )
                    }
                )
            }
            itemsIndexed(
                items = rankedTracks,
                key = { _, item -> item.first.id }
            ) { index, item ->
                val track = item.first
                val trackStats = item.second
                StatisticsTrackRow(
                    track = track,
                    index = index,
                    trackStats = trackStats,
                    totalMs = stats.totalMs,
                    isCurrent = track.id == currentTrackId,
                    isPlaying = isPlaying && track.id == currentTrackId,
                    onClick = { onTrackAction(track) },
                    modifier = Modifier.animateItemPlacement()
                )
            }
            item(key = "footer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "聆听时长只统计实际播放的时间",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
            }
        }
    }
}

private fun conciseDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0 分钟"
    val totalMinutes = milliseconds / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> String.format(Locale.US, "%.1f 时", hours + minutes / 60f)
        hours > 0L -> "$hours 时"
        else -> "$minutes 分"
    }
}

@Composable
private fun TotalListeningHero(
    totalMs: Long,
    todayMs: Long,
    accent: Color,
    isPlaying: Boolean
) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(1_000, easing = FastOutSlowInEasing),
        label = "heroReveal"
    )
    val shownMs = (totalMs * reveal).toLong()
    val totalMinutes = shownMs / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
        borderColors = listOf(
            accent.copy(alpha = 0.36f),
            GlassBorderBright,
            GlassBorder
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TinyLabel(
                    text = "ALL TIME · 累计聆听",
                    color = accent,
                    background = accent.copy(alpha = 0.10f)
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = hours.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = CatCream
                    )
                    Text(
                        text = " 小时 ",
                        style = MaterialTheme.typography.labelLarge,
                        color = CatTextSecondary,
                        modifier = Modifier.padding(bottom = 7.dp)
                    )
                    Text(
                        text = minutes.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.headlineMedium,
                        color = accent,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = " 分",
                        style = MaterialTheme.typography.labelLarge,
                        color = CatTextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EqualizerBars(
                        active = isPlaying,
                        color = accent,
                        modifier = Modifier.size(16.dp, 12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (todayMs > 0L) {
                            "今天已经听了 ${formatCompactDuration(todayMs)}"
                        } else {
                            "今天还没有开始聆听"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            ListeningVinyl(
                accent = accent,
                isPlaying = isPlaying,
                modifier = Modifier.size(92.dp)
            )
        }
    }
}

@Composable
private fun ListeningVinyl(
    accent: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(14_000, easing = LinearEasing)
                )
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer { rotationZ = rotation.value },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.46f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.55f
                ),
                radius = size.minDimension * 0.55f,
                center = center
            )
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFF221A2D),
                        accent.copy(alpha = 0.45f),
                        Color(0xFF18121F),
                        accent.copy(alpha = 0.25f),
                        Color(0xFF221A2D)
                    )
                ),
                radius = radius,
                center = center
            )
            listOf(0.92f, 0.78f, 0.64f, 0.50f).forEach { scale ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.07f),
                    radius = radius * scale,
                    center = center,
                    style = Stroke(0.8.dp.toPx())
                )
            }
            drawCircle(
                color = accent.copy(alpha = 0.85f),
                radius = radius * 0.16f,
                center = center
            )
            drawCircle(
                color = Color(0xFF17101E),
                radius = radius * 0.05f,
                center = center
            )
            drawLine(
                color = Color.White.copy(alpha = 0.16f),
                start = center,
                end = Offset(center.x + radius * 0.7f, center.y - radius * 0.45f),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(21.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 13.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp
                ),
                color = CatCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WeeklyChart(
    dailyMs: Map<String, Long>,
    accent: Color
) {
    val today = LocalDate.now()
    val dates = remember(today) { (6 downTo 0).map { today.minusDays(it.toLong()) } }
    val values = remember(dailyMs, dates) { dates.map { dailyMs[dayKey(it)] ?: 0L } }
    val maxValue = max(1L, values.maxOrNull() ?: 0L)
    val todayValue = values.lastOrNull() ?: 0L

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "最近 7 天",
                        style = MaterialTheme.typography.titleMedium,
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "聆听节奏",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
                TinyLabel(
                    text = "今天 · ${formatCompactDuration(todayValue)}",
                    color = accent,
                    background = accent.copy(alpha = 0.10f)
                )
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth()) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .padding(bottom = 0.dp)
                ) {
                    for (index in 1..3) {
                        val y = size.height * (index / 4f)
                        drawLine(
                            color = Color.White.copy(alpha = 0.035f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dates.forEachIndexed { index, date ->
                        DayBar(
                            value = values[index],
                            maxValue = maxValue,
                            isToday = index == dates.lastIndex,
                            label = if (date == today) "今" else weekdayShort(date),
                            accent = accent,
                            index = index,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayBar(
    value: Long,
    maxValue: Long,
    isToday: Boolean,
    label: String,
    accent: Color,
    index: Int,
    modifier: Modifier = Modifier
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 48L)
        appeared = true
    }
    val fraction = if (value > 0L) {
        (value.toFloat() / maxValue.toFloat()).coerceIn(0.05f, 1f)
    } else {
        0f
    }
    val animatedFraction by animateFloatAsState(
        targetValue = if (appeared) fraction else 0f,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "barFraction"
    )
    val color = if (isToday) accent else Color.White.copy(alpha = 0.20f)

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (animatedFraction > 0.001f) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .fillMaxHeight(animatedFraction)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    color,
                                    color.copy(alpha = if (isToday) 0.34f else 0.08f)
                                )
                            )
                        )
                )
            } else {
                Box(
                    Modifier
                        .size(width = 20.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.06f))
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .then(
                    if (isToday) {
                        Modifier
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) accent else CatTextTertiary
            )
        }
    }
}

@Composable
private fun StatisticsTrackRow(
    track: Track,
    index: Int,
    trackStats: TrackStats,
    totalMs: Long,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = remember(track.id) { coverPalette(track.id).last() }
    val share = if (totalMs > 0L) {
        (trackStats.listenedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    var appeared by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) {
        kotlinx.coroutines.delay(index.coerceAtMost(10) * 26L)
        appeared = true
    }
    val displayedShare by animateFloatAsState(
        targetValue = if (appeared) share else 0f,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "statShare"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(
                if (isCurrent) Color.White.copy(alpha = 0.065f)
                else Color.White.copy(alpha = 0.025f)
            )
            .bouncyClickable(pressScale = 0.985f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (index < 3) accent else CatTextTertiary,
            modifier = Modifier.width(24.dp)
        )
        Box {
            CoverArt(
                artworkFile = track.artworkFile,
                seed = track.id,
                cornerRadius = 13.dp,
                modifier = Modifier.size(46.dp)
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(Color(0xD9110E18)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerBars(
                        active = isPlaying,
                        color = accent,
                        modifier = Modifier.size(8.dp, 7.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = CatCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "单曲 ${formatSongDuration(track.durationMs)} · 播放 ${trackStats.playCount} 次",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.055f))
            ) {
                if (displayedShare > 0.001f) {
                    Box(
                        Modifier
                            .fillMaxWidth(displayedShare)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accent.copy(alpha = 0.8f), CatViolet.copy(alpha = 0.45f))
                                )
                            )
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (trackStats.listenedMs > 0L) {
                    formatCompactDuration(trackStats.listenedMs)
                } else {
                    "未聆听"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (trackStats.listenedMs > 0L) accent else CatTextTertiary,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (share > 0f) {
                    "${(share * 100f).roundToInt()}%"
                } else {
                    "0%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = CatTextTertiary,
                modifier = Modifier.alpha(0.7f)
            )
        }
    }
}

@Composable
private fun StatsEmptyState(onShowImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatMascot(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        )
        Text(
            text = "还没有聆听记录",
            style = MaterialTheme.typography.headlineMedium,
            color = CatCream
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "播放一首歌，CatMusic 会悄悄记下\n你和旋律相处的每一秒。",
            style = MaterialTheme.typography.bodyMedium,
            color = CatTextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(22.dp))
        GradientCtaButton(
            text = "去导入音乐",
            onClick = onShowImport
        )
    }
}
