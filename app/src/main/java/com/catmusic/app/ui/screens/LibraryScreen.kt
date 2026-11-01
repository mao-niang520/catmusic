package com.catmusic.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catmusic.app.R
import com.catmusic.app.data.ListeningStats
import com.catmusic.app.data.Track
import com.catmusic.app.ui.components.AppLogo
import com.catmusic.app.ui.components.CatMascot
import com.catmusic.app.ui.components.CoverArt
import com.catmusic.app.ui.components.EmptyHint
import com.catmusic.app.ui.components.EqualizerBars
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.GradientCtaButton
import com.catmusic.app.ui.components.LiquidGlassSurface
import com.catmusic.app.ui.components.GradientTitle
import com.catmusic.app.ui.components.PlayerUiSnapshot
import com.catmusic.app.ui.components.SectionHeader
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatDanger
import com.catmusic.app.ui.theme.CatLime
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.ui.theme.GlassBorder
import com.catmusic.app.ui.theme.GlassBorderBright
import com.catmusic.app.util.formatCompactDuration
import com.catmusic.app.util.formatSongDuration
import com.catmusic.app.util.formatStatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    statsFlow: StateFlow<ListeningStats>,
    player: PlayerUiSnapshot,
    onTrackAction: (Track) -> Unit,
    onShowImport: () -> Unit,
    onDeleteTrack: (Track) -> Unit,
    onOpenTrackInfo: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTrackId = player.currentMediaId
    val isPlaying = player.isPlaying
    var query by rememberSaveable { mutableStateOf("") }
    val filteredTracks = remember(tracks, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            tracks
        } else {
            tracks.filter { track ->
                track.title.contains(normalized, ignoreCase = true) ||
                    track.artist.contains(normalized, ignoreCase = true) ||
                    track.album.contains(normalized, ignoreCase = true)
            }
        }
    }
    val featured = tracks.firstOrNull()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header") {
            LibraryHeader(trackCount = tracks.size, onShowImport = onShowImport)
        }

        if (tracks.isNotEmpty()) {
            item(key = "search") {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (query.isBlank()) {
                item(key = "today") {
                    TodayListeningCard(
                        statsFlow = statsFlow,
                        trackCount = tracks.size,
                        isPlaying = isPlaying
                    )
                }
                if (featured != null) {
                    item(key = "featured") {
                        FeaturedTrackCard(
                            track = featured,
                            isCurrent = featured.id == currentTrackId,
                            isPlaying = isPlaying && featured.id == currentTrackId,
                            onClick = { onTrackAction(featured) }
                        )
                    }
                }
            }
            item(key = "section") {
                SectionHeader(
                    title = if (query.isBlank()) "全部音乐" else "搜索结果",
                    subtitle = if (query.isBlank()) {
                        "共 ${tracks.size} 首 · 按最近导入排序"
                    } else {
                        "找到 ${filteredTracks.size} 首"
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (filteredTracks.isEmpty()) {
                item(key = "search-empty") {
                    EmptyHint(
                        title = "没有找到匹配的歌曲",
                        description = "试试歌名、歌手或专辑的另一个关键词",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 42.dp)
                    )
                }
            } else {
                itemsIndexed(
                    items = filteredTracks,
                    key = { _, track -> track.id }
                ) { index, track ->
                    val isCurrent = track.id == currentTrackId
                    TrackRow(
                        track = track,
                        index = index,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying && isCurrent,
                        onClick = { onTrackAction(track) },
                        onDelete = { onDeleteTrack(track) },
                        onOpenInfo = { onOpenTrackInfo(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        modifier = Modifier.animateItemPlacement()
                    )
                }
                item(key = "footer") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EqualizerBars(
                            active = isPlaying,
                            color = CatViolet.copy(alpha = 0.65f),
                            modifier = Modifier.size(20.dp, 12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "全部音乐已保存到软件内 · 共 ${tracks.size} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary
                        )
                    }
                }
            }
        } else {
            item(key = "empty") {
                EmptyLibrary(onShowImport = onShowImport)
            }
        }
    }
}

@Composable
private fun LibraryHeader(trackCount: Int, onShowImport: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now = current
            val secondsIntoMinute = current.second + current.nano / 1_000_000_000f
            delay(((60f - secondsIntoMinute) * 1000f).toLong().coerceAtLeast(1_000L))
        }
    }
    val timeText = remember(now.hour, now.minute) {
        "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
    }
    val dateText = remember(now.toLocalDate()) {
        now.format(
            DateTimeFormatter.ofPattern("M月d日 EEE", Locale.SIMPLIFIED_CHINESE)
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "CAT MUSIC",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = CatLime
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "本地音乐 · 在线轻听",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary
                )
            }
            Column(
                modifier = Modifier.padding(end = 9.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp
                    ),
                    color = CatCream.copy(alpha = 0.62f)
                )
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = CatTextTertiary.copy(alpha = 0.58f)
                )
            }
            LiquidGlassSurface(
                modifier = Modifier
                    .size(46.dp)
                    .bouncyClickable(pressScale = 0.88f, onClick = onShowImport),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp),
                borderColors = listOf(
                    Color.White.copy(alpha = 0.20f),
                    GlassBorder,
                    GlassBorder
                )
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = "导入音乐",
                        tint = CatCream,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        GradientTitle(
            text = "我的音乐馆",
            style = MaterialTheme.typography.headlineLarge,
            colors = listOf(CatCream, CatCream, CatLime)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (trackCount == 0) {
                "把喜欢的声音，留在这里。"
            } else {
                "共 $trackCount 首 · 按最近导入排序"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = CatTextSecondary
        )
    }
}

@Composable
private fun TodayListeningCard(
    statsFlow: StateFlow<ListeningStats>,
    trackCount: Int,
    isPlaying: Boolean
) {
    val stats by statsFlow.collectAsStateWithLifecycle()
    val todayMs = stats.dailyMs[LocalDate.now().toString()] ?: 0L
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        borderColors = listOf(
            CatMint.copy(alpha = 0.26f),
            GlassBorderBright,
            GlassBorder
        )
    ) {
        Box(
            Modifier.drawBehind {
                // Keep the glow tied to this box's measured size. Drawing it
                // from a fillMaxSize child inside an unbounded list item gave
                // the shader a zero-height surface, which some GPUs render as
                // an opaque black wedge instead of a translucent mint bloom.
                val radius = size.width * 0.62f
                val center = Offset(size.width * 0.98f, -size.height * 0.16f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CatMint.copy(alpha = 0.10f), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TinyLabel(
                        text = "今日聆听",
                        color = CatMint,
                        background = CatMint.copy(alpha = 0.10f)
                    )
                    Spacer(Modifier.weight(1f))
                    EqualizerBars(
                        active = isPlaying,
                        color = CatMint,
                        modifier = Modifier.size(20.dp, 14.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = formatStatDuration(todayMs),
                    style = MaterialTheme.typography.headlineMedium,
                    color = CatCream
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "曲库 $trackCount 首  ·  累计 ${formatCompactDuration(stats.totalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextSecondary
                )
            }
        }
    }
}

@Composable
private fun FeaturedTrackCard(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val palette = remember(track.id) { coverPalette(track.id) }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressScale = 0.982f, onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(13.dp),
        borderColors = listOf(
            palette.last().copy(alpha = 0.34f),
            GlassBorderBright,
            GlassBorder
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                artworkFile = track.artworkFile,
                seed = track.id,
                cornerRadius = 21.dp,
                modifier = Modifier.size(86.dp)
            )
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                TinyLabel(
                    text = if (isCurrent) "正在播放" else "最近加入",
                    color = palette.last(),
                    background = palette.last().copy(alpha = 0.12f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CatCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${track.artist} · ${formatSongDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                CatViolet.copy(alpha = 0.45f),
                                palette.last().copy(alpha = 0.32f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    EqualizerBars(
                        active = true,
                        color = CatCream,
                        modifier = Modifier.size(17.dp, 16.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = "播放",
                        tint = CatCream,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val borderColor by animateColorAsState(
        targetValue = if (focused) CatViolet.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(260),
        label = "searchBorder"
    )
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        borderColors = listOf(borderColor, GlassBorderBright, GlassBorder),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = if (focused) CatViolet else CatTextTertiary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索歌曲、歌手或专辑",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatTextTertiary
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = CatCream),
                cursorBrush = SolidColor(CatViolet),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .bouncyClickable(pressScale = 0.82f) { onQueryChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "清空搜索",
                    tint = CatTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onOpenInfo: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    var appeared by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) { appeared = true }
    val entryAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 420, delayMillis = (index.coerceAtMost(10) * 22)),
        label = "rowAlpha"
    )
    val entryOffset by animateFloatAsState(
        targetValue = if (appeared) 0f else 16f,
        animationSpec = tween(durationMillis = 420, delayMillis = (index.coerceAtMost(10) * 22)),
        label = "rowOffset"
    )
    var menuOpen by remember(track.id) { mutableStateOf(false) }
    val accent = remember(track.id) { coverPalette(track.id).last() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entryAlpha
                translationY = entryOffset.dp.toPx()
            }
            .clip(RoundedCornerShape(21.dp))
            .background(
                if (isCurrent) {
                    Brush.linearGradient(
                        listOf(CatViolet.copy(alpha = 0.13f), accent.copy(alpha = 0.055f))
                    )
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            .then(
                if (isCurrent) {
                    Modifier.border(
                        1.dp,
                        Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(21.dp)
                    )
                } else {
                    Modifier
                }
            )
            .bouncyClickable(pressScale = 0.985f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                CoverArt(
                    artworkFile = track.artworkFile,
                    seed = track.id,
                    cornerRadius = 15.dp,
                    modifier = Modifier.size(54.dp)
                )
                if (isCurrent) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xD9110E18)),
                        contentAlignment = Alignment.Center
                    ) {
                        EqualizerBars(
                            active = isPlaying,
                            color = accent,
                            barCount = 3,
                            modifier = Modifier.size(9.dp, 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = if (isCurrent) CatCream else CatCream.copy(alpha = 0.94f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(track.artist)
                        if (track.album.isNotBlank() && track.album != "未知专辑") {
                            append(" · ")
                            append(track.album)
                        }
                        append(" · ")
                        append(formatSongDuration(track.durationMs))
                        if (!track.lyricsFile.isNullOrBlank()) {
                            append(" · 词")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) CatTextSecondary else CatTextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent && isPlaying) {
                EqualizerBars(
                    active = true,
                    color = accent,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(16.dp, 16.dp)
                )
            }
            Box {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .bouncyClickable(pressScale = 0.82f) { menuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = "更多选项",
                        tint = CatTextTertiary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1424))
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isCurrent && isPlaying) "暂停播放" else "播放",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CatCream
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(
                                    if (isCurrent && isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                                ),
                                contentDescription = null,
                                tint = CatViolet,
                                modifier = Modifier.size(19.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "添加到歌单",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CatCream
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_playlist),
                                contentDescription = null,
                                tint = CatMint,
                                modifier = Modifier.size(19.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "歌曲信息",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CatCream
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = CatViolet,
                                modifier = Modifier.size(19.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onOpenInfo()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "从软件中删除",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CatDanger
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = CatDanger,
                                modifier = Modifier.size(19.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onShowImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatMascot(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )
        Text(
            text = "音乐馆还没开张",
            style = MaterialTheme.typography.headlineMedium,
            color = CatCream
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = "从手机里挑几首歌，让猫猫替你收藏。\n支持 MP3、FLAC、WAV、AAC、OGG、Opus、M4A 等常见格式。",
            style = MaterialTheme.typography.bodyMedium,
            color = CatTextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(24.dp))
        GradientCtaButton(
            text = "导入第一批音乐",
            onClick = onShowImport
        )
        Spacer(Modifier.height(22.dp))
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 17.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = CatPeach,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    text = "也可以导入整个音乐文件夹。歌曲会复制进软件，之后删除原文件不影响播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
