package com.catmusic.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catmusic.app.data.HomeBackgroundStore
import com.catmusic.app.data.ImportState
import com.catmusic.app.data.Track
import com.catmusic.app.ui.components.AppTab
import com.catmusic.app.ui.components.CatBottomBar
import com.catmusic.app.ui.components.GlassBackdrop
import com.catmusic.app.ui.components.GlassBackdropView
import com.catmusic.app.ui.components.MiniPlayer
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.components.rememberPlayerUiSnapshot
import com.catmusic.app.ui.screens.ImportBottomSheet
import com.catmusic.app.ui.screens.ImportOverlay
import com.catmusic.app.ui.screens.LibraryScreen
import com.catmusic.app.ui.screens.NowPlayingScreen
import com.catmusic.app.ui.screens.OnlineMusicScreen
import com.catmusic.app.ui.screens.PlaylistPickerSheet
import com.catmusic.app.ui.screens.PlaylistsScreen
import com.catmusic.app.ui.screens.SettingsScreen
import com.catmusic.app.ui.screens.StatsScreen
import com.catmusic.app.ui.screens.TrackInfoSheet
import com.catmusic.app.ui.theme.CatBg
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatDanger
import com.catmusic.app.ui.theme.CatTextPrimary
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatViolet
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CatMusicApp(viewModel: CatMusicViewModel = viewModel()) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val controller by viewModel.controller.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val quickLinks by viewModel.quickLinks.collectAsStateWithLifecycle()
    val homeBackgroundStyle by viewModel.homeBackgroundStyle.collectAsStateWithLifecycle()
    val customHomeBackgroundName by viewModel.customHomeBackground.collectAsStateWithLifecycle()
    val audioReactiveEnabled by viewModel.audioReactiveEnabled.collectAsStateWithLifecycle()
    val audioVisualLevel by viewModel.audioVisualLevel.collectAsStateWithLifecycle()
    val lyricFrameRate by viewModel.lyricFrameRate.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val player = rememberPlayerUiSnapshot(controller)

    val context = LocalContext.current
    val customHomeBackgroundFile = remember(customHomeBackgroundName) {
        HomeBackgroundStore.file(context, customHomeBackgroundName)
    }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Library) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var artworkTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var lyricsTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var forcedLandscape by rememberSaveable { mutableStateOf(false) }
    var infoTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    var addToPlaylistTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteTrack by remember { mutableStateOf<Track?>(null) }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val chromeAlpha by animateFloatAsState(
        targetValue = if (playerOpen) 0f else 1f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "mainChromeAlpha"
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val openFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importDocumentUris(uris)
        }
    }
    val openMixedFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importMixedDocumentUris(uris)
        }
    }
    val openFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.importFolder(uri)
        }
    }
    val openHomeBackgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCustomHomeBackground(uri)
        }
    }
    val artworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = artworkTargetId
        artworkTargetId = null
        if (uri != null && targetId != null) {
            viewModel.setCustomArtwork(targetId, uri)
        }
    }
    val lyricsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = lyricsTargetId
        lyricsTargetId = null
        if (uri != null && targetId != null) {
            viewModel.importLyrics(targetId, uri)
        }
    }

    val requestArtwork: (Track) -> Unit = { track ->
        artworkTargetId = track.id
        artworkLauncher.launch(arrayOf("image/*"))
    }
    val requestLyrics: (Track) -> Unit = { track ->
        lyricsTargetId = track.id
        lyricsLauncher.launch(arrayOf("*/*"))
    }
    val activity = context.findActivity()
    val toggleOrientation: () -> Unit = {
        if (isLandscape) {
            forcedLandscape = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            forcedLandscape = true
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        Unit
    }

    LaunchedEffect(playerOpen, forcedLandscape) {
        if (!playerOpen && forcedLandscape) {
            forcedLandscape = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val audioReactiveOn = audioReactiveEnabled
    val toggleAudioReactive: () -> Unit = {
        viewModel.setAudioReactiveEnabled(!audioReactiveEnabled)
    }

    val playFromQueue: (Track, List<Track>) -> Unit = { track, queue ->
        if (player.currentMediaId == track.id) {
            viewModel.togglePlayback()
        } else {
            requestNotificationPermission()
            viewModel.playTrack(track, queue)
        }
    }
    val playAllFromQueue: (Track, List<Track>) -> Unit = { track, queue ->
        requestNotificationPermission()
        viewModel.playQueueFromStart(track, queue)
    }
    val onTrackAction: (Track) -> Unit = { track -> playFromQueue(track, tracks) }
    val openTrackInfo: (Track) -> Unit = { track -> infoTrackId = track.id }

    val currentMediaItem = controller?.currentMediaItem
    val currentTrack: Track? = remember(player.currentMediaId, tracks, currentMediaItem) {
        val mediaId = player.currentMediaId
        if (mediaId == null) {
            null
        } else {
            tracks.firstOrNull { it.id == mediaId } ?: currentMediaItem?.let { item ->
                val metadata = item.mediaMetadata
                Track(
                    id = mediaId,
                    uri = item.localConfiguration?.uri?.toString().orEmpty(),
                    title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "未知音频",
                    artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "未知艺术家",
                    album = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: "本地播放",
                    durationMs = player.durationMs,
                    artworkFile = metadata.artworkUri?.lastPathSegment,
                    mimeType = item.localConfiguration?.mimeType,
                    addedAt = 0L
                )
            }
        }
    }
    LaunchedEffect(
        currentTrack?.id,
        currentTrack?.artworkFile,
        homeBackgroundStyle,
        customHomeBackgroundFile
    ) {
        val backdrop = GlassBackdrop.current() as? GlassBackdropView ?: return@LaunchedEffect
        val track = currentTrack
        if (track != null) {
            val artwork = track.artworkFile
                ?.let { name -> File(context.filesDir, "artwork/$name") }
                ?.takeIf { it.exists() }
            if (artwork != null) {
                backdrop.setCover(artwork)
            } else {
                // No embedded/custom image: keep the album identity with the
                // same deterministic two-colour palette used by the cover UI.
                backdrop.setAlbumColors(
                    coverPalette(track.id).map { it.toArgb() }.toIntArray()
                )
            }
        } else {
            backdrop.setHome(homeBackgroundStyle, customHomeBackgroundFile)
        }
    }

    val infoTrack = remember(infoTrackId, tracks, currentTrack) {
        infoTrackId?.let { id ->
            tracks.firstOrNull { it.id == id } ?: currentTrack?.takeIf { it.id == id }
        }
    }
    LaunchedEffect(infoTrackId, infoTrack) {
        if (infoTrackId != null && infoTrack == null) {
            infoTrackId = null
        }
    }
    val addToPlaylistTrack = remember(addToPlaylistTrackId, tracks) {
        addToPlaylistTrackId?.let { id -> tracks.firstOrNull { it.id == id } }
    }
    LaunchedEffect(addToPlaylistTrackId, addToPlaylistTrack) {
        if (addToPlaylistTrackId != null && addToPlaylistTrack == null) {
            addToPlaylistTrackId = null
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportState.Finished -> {
                val message = buildImportResultMessage(state)
                viewModel.acknowledgeImportState()
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(
                        message = message,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long
                    )
                }
            }

            is ImportState.Failed -> {
                val message = state.message
                viewModel.acknowledgeImportState()
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(
                        message = message,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long
                    )
                }
            }

            else -> Unit
        }
    }

    LaunchedEffect(currentTrack?.id) {
        if (currentTrack == null) {
            playerOpen = false
        }
    }

    val importActive = importState is ImportState.Scanning || importState is ImportState.Importing

    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = importActive) { viewModel.cancelImport() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // The player paints over this area with its own transparent scene.
            // Fading the whole main chrome out (rather than covering it with
            // black) lets the animated native backdrop - and the glass that
            // refracts it - remain the only layer behind the player.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = chromeAlpha },
            containerColor = Color.Transparent,
            contentColor = CatTextPrimary,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!playerOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 900.dp)
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 9.dp)
                        ) {
                            AnimatedVisibility(
                                visible = currentTrack != null && selectedTab != AppTab.Online,
                                enter = fadeIn(tween(240)) + expandVertically(
                                    animationSpec = tween(330, easing = FastOutSlowInEasing),
                                    expandFrom = Alignment.Bottom
                                ),
                                exit = fadeOut(tween(140)) + shrinkVertically(
                                    animationSpec = tween(220),
                                    shrinkTowards = Alignment.Bottom
                                )
                            ) {
                                Column {
                                    currentTrack?.let { track ->
                                        MiniPlayer(
                                            track = track,
                                            player = player,
                                            onOpen = { playerOpen = true },
                                            onToggle = { viewModel.togglePlayback() }
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                            CatBottomBar(
                                selected = selectedTab,
                                onSelect = { tab ->
                                    // Switching tabs never interrupts local
                                    // playback. The online page only asks the
                                    // native player to pause at the moment an
                                    // online preview actually starts, so
                                    // browsing the shop is silent and the two
                                    // players still never overlap.
                                    selectedTab = tab
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    transitionSpec = {
                        val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        (fadeIn(tween(280)) + androidx.compose.animation.slideInHorizontally(
                            animationSpec = tween(380, easing = FastOutSlowInEasing)
                        ) { fullWidth -> direction * fullWidth / 10 })
                            .togetherWith(
                                fadeOut(tween(150)) + androidx.compose.animation.slideOutHorizontally(
                                    animationSpec = tween(260)
                                ) { fullWidth -> -direction * fullWidth / 14 }
                            )
                    },
                    label = "mainTabs"
                ) { tab ->
                    when (tab) {
                        AppTab.Library -> LibraryScreen(
                            tracks = tracks,
                            statsFlow = viewModel.stats,
                            player = player,
                            onTrackAction = onTrackAction,
                            onShowImport = { showImportSheet = true },
                            onDeleteTrack = { pendingDeleteTrack = it },
                            onOpenTrackInfo = openTrackInfo,
                            onAddToPlaylist = { addToPlaylistTrackId = it.id }
                        )

                        AppTab.Online -> OnlineMusicScreen(
                            onExit = { selectedTab = AppTab.Library },
                            onOnlinePlaybackStarted = viewModel::pausePlayback
                        )

                        AppTab.Playlists -> PlaylistsScreen(
                            playlists = playlists,
                            tracks = tracks,
                            player = player,
                            isActive = !playerOpen && !importActive,
                            onPlayTrack = playFromQueue,
                            onPlayAllFromStart = playAllFromQueue,
                            onOpenTrackInfo = openTrackInfo,
                            onCreatePlaylist = viewModel::createPlaylist,
                            onRenamePlaylist = viewModel::renamePlaylist,
                            onDeletePlaylist = viewModel::deletePlaylist,
                            onAddTracks = viewModel::addTracksToPlaylist,
                            onRemoveTrack = viewModel::removeTrackFromPlaylist,
                            onMoveTrack = viewModel::moveTrackInPlaylist,
                            onShowImport = { showImportSheet = true }
                        )

                        AppTab.Stats -> StatsScreen(
                            tracks = tracks,
                            statsFlow = viewModel.stats,
                            player = player,
                            onTrackAction = onTrackAction,
                            onShowImport = { showImportSheet = true }
                        )

                        AppTab.Settings -> SettingsScreen(
                            trackCount = tracks.size,
                            statsFlow = viewModel.stats,
                            quickLinks = quickLinks,
                            onSaveQuickLink = viewModel::saveQuickLink,
                            onDeleteQuickLink = viewModel::deleteQuickLink,
                            homeBackgroundStyle = homeBackgroundStyle,
                            customBackgroundFile = customHomeBackgroundFile,
                            onSelectHomeBackground = viewModel::setHomeBackgroundStyle,
                            onPickCustomBackground = {
                                openHomeBackgroundLauncher.launch(arrayOf("image/*"))
                            },
                            onClearCustomBackground = viewModel::clearCustomHomeBackground,
                            audioVisualLevel = audioVisualLevel,
                            onSelectAudioVisualLevel = viewModel::setAudioVisualLevel,
                            lyricFrameRate = lyricFrameRate,
                            onSelectLyricFrameRate = viewModel::setLyricFrameRate,
                            onPickMixed = { openMixedFilesLauncher.launch(arrayOf("*/*")) },
                            onPickFiles = { openFilesLauncher.launch(arrayOf("audio/*")) },
                            onPickFolder = { openFolderLauncher.launch(null) },
                            onClearLibrary = viewModel::clearLibrary,
                            onResetStats = viewModel::resetStatistics
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = playerOpen && currentTrack != null,
            enter = slideInVertically(
                initialOffsetY = { it / 9 },
                animationSpec = tween(430, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(260)),
            exit = slideOutVertically(
                targetOffsetY = { it / 12 },
                animationSpec = tween(340, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            currentTrack?.let { track ->
                NowPlayingScreen(
                    track = track,
                    player = player,
                    isLandscape = isLandscape,
                    onClose = { playerOpen = false },
                    onToggle = viewModel::togglePlayback,
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                    onSeek = viewModel::seekTo,
                    onLyricOffsetChange = { offsetMs ->
                        viewModel.setLyricOffset(track.id, offsetMs)
                    },
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeat = viewModel::cycleRepeatMode,
                    onRequestArtwork = requestArtwork,
                    onOpenTrackInfo = openTrackInfo,
                    onRequestLyrics = requestLyrics,
                    onToggleOrientation = toggleOrientation,
                    audioReactiveOn = audioReactiveOn,
                    onToggleAudioReactive = toggleAudioReactive,
                    audioVisualLevel = audioVisualLevel,
                    lyricFrameRate = lyricFrameRate,
                    sleepTimerRemainingMs = sleepTimerRemainingMs,
                    onSetSleepTimer = viewModel::setSleepTimer,
                    onClearSleepTimer = viewModel::clearSleepTimer
                )
            }
        }

        if (addToPlaylistTrack != null) {
            PlaylistPickerSheet(
                track = addToPlaylistTrack,
                playlists = playlists,
                onDismiss = { addToPlaylistTrackId = null },
                onToggleMembership = { playlist, isMember ->
                    if (isMember) {
                        viewModel.removeTrackFromPlaylist(playlist.id, addToPlaylistTrack.id)
                    } else {
                        viewModel.addTracksToPlaylist(playlist.id, listOf(addToPlaylistTrack.id))
                    }
                },
                onGoToPlaylists = {
                    addToPlaylistTrackId = null
                    selectedTab = AppTab.Playlists
                }
            )
        }

        if (infoTrack != null) {
            TrackInfoSheet(
                track = infoTrack,
                statsFlow = viewModel.stats,
                onDismiss = { infoTrackId = null },
                onSaveMetadata = { title, artist, album ->
                    viewModel.updateTrackMetadata(infoTrack, title, artist, album)
                },
                onRequestArtwork = {
                    infoTrackId = null
                    requestArtwork(infoTrack)
                },
                onResetArtwork = {
                    viewModel.resetArtwork(infoTrack)
                },
                onRequestLyrics = {
                    infoTrackId = null
                    requestLyrics(infoTrack)
                },
                onDeleteFromSoftware = {
                    pendingDeleteTrack = infoTrack
                    infoTrackId = null
                }
            )
        }

        if (pendingDeleteTrack != null) {
            val trackToDelete = pendingDeleteTrack
            AlertDialog(
                onDismissRequest = { pendingDeleteTrack = null },
                containerColor = Color(0xFF1A1424),
                titleContentColor = CatCream,
                textContentColor = CatTextSecondary,
                title = {
                    Text(
                        text = "从软件中删除？",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Text(
                        text = "将删除《${trackToDelete?.title.orEmpty()}》在软件内保存的音频副本、封面和歌词，并从所有歌单中移除。手机上的原始文件不会被删除。",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            trackToDelete?.let(viewModel::removeTrack)
                            pendingDeleteTrack = null
                        }
                    ) {
                        Text("删除副本", color = CatDanger, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteTrack = null }) {
                        Text("取消", color = CatTextSecondary)
                    }
                }
            )
        }

        if (showImportSheet) {
            ImportBottomSheet(
                onDismiss = { showImportSheet = false },
                onPickMixed = { openMixedFilesLauncher.launch(arrayOf("*/*")) },
                onPickFiles = { openFilesLauncher.launch(arrayOf("audio/*")) },
                onPickFolder = { openFolderLauncher.launch(null) }
            )
        }

        AnimatedVisibility(
            visible = importActive,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            ImportOverlay(
                state = importState,
                onCancel = viewModel::cancelImport
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(17.dp),
                containerColor = Color(0xFF211A2E),
                contentColor = CatCream,
                dismissActionContentColor = CatTextPrimary.copy(alpha = 0.7f),
                actionContentColor = CatViolet
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun buildImportResultMessage(state: ImportState.Finished): String {
    val parts = buildList {
        if (state.imported > 0) add("已导入 ${state.imported} 首")
        if (state.lyricsMatched > 0) add("配好 ${state.lyricsMatched} 份歌词")
        if (state.coversMatched > 0) add("配好 ${state.coversMatched} 张封面")
        if (state.duplicates > 0) add("跳过 ${state.duplicates} 首已存在")
        if (state.skipped > 0) add("忽略 ${state.skipped} 个文件")
        if (state.failed > 0) add("${state.failed} 个无法读取")
    }
    return if (parts.isEmpty()) {
        "没有找到可导入的音频文件"
    } else {
        parts.joinToString(" · ")
    }
}
