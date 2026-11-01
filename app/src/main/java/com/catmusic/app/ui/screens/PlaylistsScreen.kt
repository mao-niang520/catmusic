package com.catmusic.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmusic.app.R
import com.catmusic.app.data.Playlist
import com.catmusic.app.data.Track
import com.catmusic.app.ui.components.CoverArt
import com.catmusic.app.ui.components.EqualizerBars
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.GradientCtaButton
import com.catmusic.app.ui.components.GradientTitle
import com.catmusic.app.ui.components.PlayerUiSnapshot
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatDanger
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.ui.theme.GlassBorder
import com.catmusic.app.ui.theme.GlassBorderBright
import com.catmusic.app.util.formatCompactDuration
import com.catmusic.app.util.formatPlaylistUpdated
import com.catmusic.app.util.formatSongDuration
import kotlinx.coroutines.delay

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    tracks: List<Track>,
    player: PlayerUiSnapshot,
    isActive: Boolean,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onPlayAllFromStart: (Track, List<Track>) -> Unit,
    onOpenTrackInfo: (Track) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onAddTracks: (String, List<String>) -> Unit,
    onRemoveTrack: (String, String) -> Unit,
    onMoveTrack: (String, String, Int) -> Unit,
    onShowImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var openedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var createDialogOpen by rememberSaveable { mutableStateOf(false) }
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var addTracksTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    val openedPlaylist = playlists.firstOrNull { it.id == openedPlaylistId }
    val renameTarget = playlists.firstOrNull { it.id == renameTargetId }
    val deleteTarget = playlists.firstOrNull { it.id == deleteTargetId }
    val addTarget = playlists.firstOrNull { it.id == addTracksTargetId }

    LaunchedEffect(playlists, openedPlaylistId) {
        if (openedPlaylistId != null && openedPlaylist == null) {
            openedPlaylistId = null
        }
    }

    BackHandler(enabled = isActive && openedPlaylistId != null) {
        openedPlaylistId = null
    }

    AnimatedContent(
        targetState = openedPlaylistId,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)) {
                    it / 7
                } + fadeIn(tween(240)))
                    .togetherWith(fadeOut(tween(140)))
            } else {
                (slideInHorizontally(tween(300)) { -it / 9 } + fadeIn(tween(240)))
                    .togetherWith(fadeOut(tween(130)))
            }
        },
        label = "playlistNavigation"
    ) { selectedId ->
        val selected = playlists.firstOrNull { it.id == selectedId }
        if (selected == null) {
            PlaylistOverview(
                playlists = playlists,
                trackById = trackById,
                onCreate = { createDialogOpen = true },
                onOpen = { openedPlaylistId = it.id },
                onRename = { renameTargetId = it.id },
                onDelete = { deleteTargetId = it.id }
            )
        } else {
            val playlistTracks = selected.trackIds.mapNotNull(trackById::get)
            PlaylistDetail(
                playlist = selected,
                playlistTracks = playlistTracks,
                player = player,
                onBack = { openedPlaylistId = null },
                onRename = { renameTargetId = selected.id },
                onDelete = { deleteTargetId = selected.id },
                onAddTracks = { addTracksTargetId = selected.id },
                onPlayTrack = { track -> onPlayTrack(track, playlistTracks) },
                onPlayAll = { first -> onPlayAllFromStart(first, playlistTracks) },
                onOpenTrackInfo = onOpenTrackInfo,
                onRemoveTrack = { track -> onRemoveTrack(selected.id, track.id) },
                onMoveTrack = { track, offset -> onMoveTrack(selected.id, track.id, offset) }
            )
        }
    }

    if (createDialogOpen) {
        PlaylistNameDialog(
            title = "新建歌单",
            initialName = "",
            hint = "给这个歌单起一个名字",
            confirmLabel = "创建",
            onDismiss = { createDialogOpen = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                createDialogOpen = false
            }
        )
    }

    if (renameTarget != null) {
        PlaylistNameDialog(
            title = "重命名歌单",
            initialName = renameTarget.name,
            hint = "新的歌单名称",
            confirmLabel = "保存",
            onDismiss = { renameTargetId = null },
            onConfirm = { name ->
                onRenamePlaylist(renameTarget.id, name)
                renameTargetId = null
            }
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            containerColor = Color(0xFF1A1424),
            titleContentColor = CatCream,
            textContentColor = CatTextSecondary,
            title = {
                Text(
                    text = "删除歌单「${deleteTarget.name}」？",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "只会删除歌单本身。歌单里的歌曲仍会保留在音乐馆，软件内的音频副本不会被删除。",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (openedPlaylistId == deleteTarget.id) openedPlaylistId = null
                        onDeletePlaylist(deleteTarget.id)
                        deleteTargetId = null
                    }
                ) {
                    Text("删除", color = CatDanger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) {
                    Text("取消", color = CatTextSecondary)
                }
            }
        )
    }

    if (addTarget != null) {
        AddTracksToPlaylistSheet(
            playlist = addTarget,
            allTracks = tracks,
            onDismiss = { addTracksTargetId = null },
            onShowImport = {
                addTracksTargetId = null
                onShowImport()
            },
            onAdd = { ids ->
                onAddTracks(addTarget.id, ids)
                addTracksTargetId = null
            }
        )
    }
}

@Composable
private fun PlaylistOverview(
    playlists: List<Playlist>,
    trackById: Map<String, Track>,
    onCreate: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "playlist-header") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "PLAYLISTS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 3.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = CatViolet
                        )
                        Spacer(Modifier.height(8.dp))
                        GradientTitle(
                            text = "我的歌单",
                            style = MaterialTheme.typography.headlineLarge,
                            colors = listOf(CatCream, CatPeach, CatViolet)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.055f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                            .bouncyClickable(pressScale = 0.88f, onClick = onCreate),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = "新建歌单",
                            tint = CatCream,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = if (playlists.isEmpty()) {
                        "把不同的心情，收进不同的歌单。"
                    } else {
                        "共 ${playlists.size} 个歌单 · 按最近编辑排序"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatTextSecondary
                )
            }
        }

        if (playlists.isEmpty()) {
            item(key = "empty-playlists") {
                EmptyPlaylists(onCreate = onCreate)
            }
        } else {
            itemsIndexed(
                items = playlists,
                key = { _, playlist -> playlist.id }
            ) { index, playlist ->
                val playlistTracks = playlist.trackIds.mapNotNull(trackById::get)
                PlaylistCard(
                    playlist = playlist,
                    tracks = playlistTracks,
                    index = index,
                    onOpen = { onOpen(playlist) },
                    onRename = { onRename(playlist) },
                    onDelete = { onDelete(playlist) },
                    modifier = Modifier.animateItemPlacement()
                )
            }
            item(key = "playlist-footer") {
                Text(
                    text = "歌单的重命名和删除在卡片右上角，歌曲排序在歌单详情里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    tracks: List<Track>,
    index: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember(playlist.id) { mutableStateOf(false) }
    val palette = remember(playlist.id) { coverPalette(playlist.id) }
    var appeared by remember(playlist.id) { mutableStateOf(false) }
    LaunchedEffect(playlist.id) {
        delay(index.coerceAtMost(6) * 30L)
        appeared = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(380),
        label = "playlistCardAlpha"
    )

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .bouncyClickable(pressScale = 0.985f, onClick = onOpen),
        shape = RoundedCornerShape(27.dp),
        contentPadding = PaddingValues(12.dp),
        borderColors = listOf(
            palette.last().copy(alpha = 0.26f),
            GlassBorderBright,
            GlassBorder
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaylistMosaic(
                tracks = tracks,
                seed = playlist.id,
                modifier = Modifier.size(84.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = CatCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = buildString {
                        append("${tracks.size} 首")
                        val duration = tracks.sumOf { it.durationMs }
                        if (duration > 0L) {
                            append(" · ")
                            append(formatCompactDuration(duration))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatPlaylistUpdated(playlist.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatTextTertiary
                )
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Box(
                    modifier = Modifier
                        .size(35.dp)
                        .clip(CircleShape)
                        .bouncyClickable(pressScale = 0.82f) { menuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = "歌单选项",
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
                        text = { PlaylistMenuLabel("重命名") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = null,
                                tint = CatViolet,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { PlaylistMenuLabel("删除歌单", CatDanger) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = CatDanger,
                                modifier = Modifier.size(18.dp)
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
private fun PlaylistDetail(
    playlist: Playlist,
    playlistTracks: List<Track>,
    player: PlayerUiSnapshot,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddTracks: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (Track) -> Unit,
    onOpenTrackInfo: (Track) -> Unit,
    onRemoveTrack: (Track) -> Unit,
    onMoveTrack: (Track, Int) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val palette = remember(playlist.id) { coverPalette(playlist.id) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "detail-top") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.055f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                        .bouncyClickable(pressScale = 0.85f, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回歌单",
                        tint = CatCream,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "PLAYLIST",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 2.7.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = CatTextTertiary
                )
                Spacer(Modifier.weight(1f))
                Box {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.035f))
                            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
                            .bouncyClickable(pressScale = 0.86f) { menuOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more),
                            contentDescription = "歌单选项",
                            tint = CatTextSecondary,
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
                            text = { PlaylistMenuLabel("重命名歌单") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = null,
                                    tint = CatViolet,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { PlaylistMenuLabel("删除歌单", CatDanger) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                    tint = CatDanger,
                                    modifier = Modifier.size(18.dp)
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

        item(key = "detail-header") {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(16.dp),
                borderColors = listOf(
                    palette.last().copy(alpha = 0.34f),
                    GlassBorderBright,
                    GlassBorder
                )
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlaylistMosaic(
                            tracks = playlistTracks,
                            seed = playlist.id,
                            modifier = Modifier.size(102.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            TinyLabel(
                                text = "CURATED · ${playlistTracks.size} 首",
                                color = palette.last(),
                                background = palette.last().copy(alpha = 0.11f)
                            )
                            Spacer(Modifier.height(9.dp))
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = CatCream,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = buildString {
                                    append(formatCompactDuration(playlistTracks.sumOf { it.durationMs }))
                                    append(" · ")
                                    append(formatPlaylistUpdated(playlist.updatedAt))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = CatTextTertiary
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        val canPlay = playlistTracks.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .weight(1.25f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (canPlay) {
                                        Brush.linearGradient(listOf(CatViolet, CatPeach))
                                    } else {
                                        Brush.linearGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.06f),
                                                Color.White.copy(alpha = 0.03f)
                                            )
                                        )
                                    }
                                )
                                .bouncyClickable(
                                    enabled = canPlay,
                                    pressScale = 0.97f
                                ) {
                                    playlistTracks.firstOrNull()?.let(onPlayAll)
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play),
                                    contentDescription = null,
                                    tint = if (canPlay) Color(0xFF231630) else CatTextTertiary,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    text = "播放全部",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (canPlay) Color(0xFF231630) else CatTextTertiary
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.055f))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.08f),
                                    RoundedCornerShape(16.dp)
                                )
                                .bouncyClickable(pressScale = 0.97f, onClick = onAddTracks)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add),
                                    contentDescription = null,
                                    tint = CatCream,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "添加歌曲",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CatCream
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "detail-section") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "歌曲列表",
                    style = MaterialTheme.typography.titleLarge,
                    color = CatCream
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "${playlistTracks.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "⋮ 可排序",
                    style = MaterialTheme.typography.labelSmall,
                    color = CatTextTertiary.copy(alpha = 0.7f)
                )
            }
        }

        if (playlistTracks.isEmpty()) {
            item(key = "empty-detail") {
                EmptyPlaylistDetail(onAddTracks = onAddTracks)
            }
        } else {
            itemsIndexed(
                items = playlistTracks,
                key = { _, track -> track.id }
            ) { index, track ->
                val isCurrent = player.currentMediaId == track.id
                PlaylistTrackRow(
                    track = track,
                    index = index,
                    totalCount = playlistTracks.size,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && player.isPlaying,
                    onPlay = { onPlayTrack(track) },
                    onInfo = { onOpenTrackInfo(track) },
                    onRemove = { onRemoveTrack(track) },
                    onMoveUp = { onMoveTrack(track, -1) },
                    onMoveDown = { onMoveTrack(track, 1) }
                )
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    index: Int,
    totalCount: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var menuOpen by remember(track.id) { mutableStateOf(false) }
    val palette = remember(track.id) { coverPalette(track.id) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(
                if (isCurrent) {
                    Brush.linearGradient(
                        listOf(CatViolet.copy(alpha = 0.12f), palette.last().copy(alpha = 0.05f))
                    )
                } else {
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.025f), Color.White.copy(alpha = 0.012f)))
                }
            )
            .bouncyClickable(pressScale = 0.985f, onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (isCurrent) palette.last() else CatTextTertiary,
            modifier = Modifier.width(26.dp)
        )
        Box {
            CoverArt(
                artworkFile = track.artworkFile,
                seed = track.id,
                cornerRadius = 13.dp,
                modifier = Modifier.size(48.dp)
            )
            if (isCurrent) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(Color(0xD9110E18)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerBars(
                        active = isPlaying,
                        color = palette.last(),
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
                text = "${track.artist} · ${formatSongDuration(track.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .bouncyClickable(pressScale = 0.82f) { menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = "歌曲选项",
                    tint = CatTextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1424))
            ) {
                PlaylistTrackMenuItem(
                    iconRes = R.drawable.ic_info,
                    label = "歌曲信息",
                    tint = CatViolet
                ) {
                    menuOpen = false
                    onInfo()
                }
                PlaylistTrackMenuItem(
                    iconRes = R.drawable.ic_move_up,
                    label = "上移",
                    tint = CatPeach,
                    enabled = index > 0
                ) {
                    menuOpen = false
                    onMoveUp()
                }
                PlaylistTrackMenuItem(
                    iconRes = R.drawable.ic_move_down,
                    label = "下移",
                    tint = CatPeach,
                    enabled = index < totalCount - 1
                ) {
                    menuOpen = false
                    onMoveDown()
                }
                PlaylistTrackMenuItem(
                    iconRes = R.drawable.ic_delete,
                    label = "移出歌单",
                    tint = CatDanger
                ) {
                    menuOpen = false
                    onRemove()
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackMenuItem(
    iconRes: Int,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) CatCream else CatTextTertiary.copy(alpha = 0.4f)
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size(18.dp)
            )
        },
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun PlaylistMosaic(
    tracks: List<Track>,
    seed: String,
    modifier: Modifier = Modifier
) {
    val palette = remember(seed) { coverPalette(seed) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(palette.first().copy(alpha = 0.75f), palette.last().copy(alpha = 0.45f))
                )
            )
    ) {
        when {
            tracks.isEmpty() -> {
                Icon(
                    painter = painterResource(R.drawable.ic_playlist),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                )
            }

            tracks.size == 1 -> {
                MosaicCell(track = tracks[0], modifier = Modifier.fillMaxSize())
            }

            tracks.size == 2 -> {
                Row(Modifier.fillMaxSize()) {
                    MosaicCell(track = tracks[0], modifier = Modifier.weight(1f).fillMaxHeight())
                    MosaicCell(track = tracks[1], modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }

            tracks.size == 3 -> {
                Row(Modifier.fillMaxSize()) {
                    MosaicCell(track = tracks[0], modifier = Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        MosaicCell(track = tracks[1], modifier = Modifier.weight(1f).fillMaxWidth())
                        MosaicCell(track = tracks[2], modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }

            else -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MosaicCell(track = tracks[0], modifier = Modifier.weight(1f).fillMaxHeight())
                        MosaicCell(track = tracks[1], modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MosaicCell(track = tracks[2], modifier = Modifier.weight(1f).fillMaxHeight())
                        MosaicCell(track = tracks[3], modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f))
                    )
                )
        )
    }
}

@Composable
private fun MosaicCell(track: Track, modifier: Modifier = Modifier) {
    CoverArt(
        artworkFile = track.artworkFile,
        seed = track.id,
        cornerRadius = 0.dp,
        showBorder = false,
        modifier = modifier
    )
}

@Composable
private fun EmptyPlaylists(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(170.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .rotate(-12f)
                    .clip(RoundedCornerShape(25.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(CatViolet.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(25.dp))
            )
            Box(
                Modifier
                    .size(112.dp)
                    .rotate(9f)
                    .clip(RoundedCornerShape(25.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(CatPeach.copy(alpha = 0.22f), CatViolet.copy(alpha = 0.10f))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(25.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_playlist),
                    contentDescription = null,
                    tint = CatCream.copy(alpha = 0.82f),
                    modifier = Modifier.size(40.dp)
                )
            }
            TinyLabel(
                text = "NEW",
                color = CatMint,
                background = CatMint.copy(alpha = 0.11f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 9.dp, end = 6.dp)
            )
        }
        Text(
            text = "还没有歌单",
            style = MaterialTheme.typography.headlineMedium,
            color = CatCream
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "为通勤、夜晚或某一刻的心情\n建一个只属于你的歌单。",
            style = MaterialTheme.typography.bodyMedium,
            color = CatTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(20.dp))
        GradientCtaButton(
            text = "新建第一个歌单",
            iconRes = R.drawable.ic_add,
            onClick = onCreate
        )
    }
}

@Composable
private fun EmptyPlaylistDetail(onAddTracks: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(CatViolet.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_playlist),
                contentDescription = null,
                tint = CatViolet.copy(alpha = 0.8f),
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(13.dp))
        Text(
            text = "这个歌单还是空的",
            style = MaterialTheme.typography.titleMedium,
            color = CatCream
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "从音乐馆挑选几首歌，放进这段心情里。",
            style = MaterialTheme.typography.bodySmall,
            color = CatTextTertiary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(17.dp))
        GradientCtaButton(
            text = "添加歌曲",
            iconRes = R.drawable.ic_add,
            onClick = onAddTracks
        )
    }
}

@Composable
private fun AddTracksToPlaylistSheet(
    playlist: Playlist,
    allTracks: List<Track>,
    onDismiss: () -> Unit,
    onShowImport: () -> Unit,
    onAdd: (List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val availableTracks = remember(playlist, allTracks) {
        allTracks.filterNot { it.id in playlist.trackIds }
    }
    var selected by remember(playlist.id) { mutableStateOf<Set<String>>(emptySet()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF15101D),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "添加歌曲",
                        style = MaterialTheme.typography.headlineSmall,
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "添加到「${playlist.name}」 · 已选 ${selected.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .bouncyClickable(pressScale = 0.84f, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "关闭",
                        tint = CatTextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (availableTracks.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_playlist),
                        contentDescription = null,
                        tint = CatTextTertiary,
                        modifier = Modifier.size(35.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (allTracks.isEmpty()) "音乐馆还没有歌曲" else "所有歌曲都已经在这个歌单里",
                        style = MaterialTheme.typography.titleMedium,
                        color = CatCream
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = if (allTracks.isEmpty()) "先去导入一些音乐吧。" else "可以回到歌单调整歌曲顺序。",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                    if (allTracks.isEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        GradientCtaButton(
                            text = "去导入音乐",
                            iconRes = R.drawable.ic_add,
                            onClick = onShowImport
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    itemsIndexed(
                        items = availableTracks,
                        key = { _, track -> track.id }
                    ) { _, track ->
                        val isSelected = track.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(17.dp))
                                .background(
                                    if (isSelected) CatViolet.copy(alpha = 0.09f)
                                    else Color.White.copy(alpha = 0.025f)
                                )
                                .bouncyClickable(pressScale = 0.985f) {
                                    selected = if (isSelected) selected - track.id else selected + track.id
                                }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverArt(
                                artworkFile = track.artworkFile,
                                seed = track.id,
                                cornerRadius = 12.dp,
                                modifier = Modifier.size(45.dp)
                            )
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
                                    text = "${track.artist} · ${formatSongDuration(track.durationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CatTextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(23.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) CatViolet else Color.White.copy(alpha = 0.055f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) CatViolet else Color.White.copy(alpha = 0.10f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = "已选择",
                                        tint = Color(0xFF1D1427),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected.isEmpty()) {
                                Brush.linearGradient(
                                    listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.03f))
                                )
                            } else {
                                Brush.linearGradient(listOf(CatViolet, CatPeach))
                            }
                        )
                        .bouncyClickable(
                            enabled = selected.isNotEmpty(),
                            pressScale = 0.97f
                        ) { onAdd(selected.toList()) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selected.isEmpty()) {
                            "选择要添加的歌曲"
                        } else {
                            "添加 ${selected.size} 首歌曲"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (selected.isEmpty()) CatTextTertiary else Color(0xFF231630)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        delay(160)
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1424),
        titleContentColor = CatCream,
        textContentColor = CatTextSecondary,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.055f))
                        .border(1.dp, CatViolet.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 13.dp, vertical = 12.dp)
                ) {
                    if (name.isBlank()) {
                        Text(
                            text = "例如：深夜循环",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CatTextTertiary.copy(alpha = 0.7f)
                        )
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { if (it.length <= 40) name = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = CatCream),
                        cursorBrush = SolidColor(CatViolet),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val clean = name.trim()
                                if (clean.isNotEmpty()) {
                                    focusManager.clearFocus()
                                    onConfirm(clean)
                                }
                            }
                        )
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "${name.length} / 40",
                    style = MaterialTheme.typography.labelSmall,
                    color = CatTextTertiary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) {
                Text(
                    text = confirmLabel,
                    color = if (name.isNotBlank()) CatViolet else CatTextTertiary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = CatTextSecondary)
            }
        }
    )
}

@Composable
private fun PlaylistMenuLabel(text: String, color: Color = CatCream) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}
