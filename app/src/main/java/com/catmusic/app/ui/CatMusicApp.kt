package com.catmusic.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catmusic.app.data.AdaptiveMotion
import com.catmusic.app.data.HomeBackgroundStore
import com.catmusic.app.data.HomeBackgroundStyle
import com.catmusic.app.data.ImportState
import com.catmusic.app.data.Track
import com.catmusic.app.online.OnlineAudioBus
import com.catmusic.app.ui.components.AppTab
import com.catmusic.app.ui.components.CatBottomBar
import com.catmusic.app.ui.components.GlassBackdrop
import com.catmusic.app.ui.components.GlassBackdropView
import com.catmusic.app.ui.components.LocalAdaptiveMotionNanos
import com.catmusic.app.ui.components.LocalMotionFrameRate
import com.catmusic.app.ui.components.MiniPlayer
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.components.rememberAdaptiveMotionNanos
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
import kotlinx.coroutines.delay
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
    val lyricTranslationMode by viewModel.lyricTranslationMode.collectAsStateWithLifecycle()
    val fadeInMs by viewModel.fadeInMs.collectAsStateWithLifecycle()
    val fadeOutMs by viewModel.fadeOutMs.collectAsStateWithLifecycle()
    val desktopLyricsEnabled by viewModel.desktopLyricsEnabled.collectAsStateWithLifecycle()
    val libraryLayoutStyle by viewModel.libraryLayoutStyle.collectAsStateWithLifecycle()
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
    var onlineTabOpened by rememberSaveable { mutableStateOf(false) }
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

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            viewModel.setDesktopLyricsEnabled(true)
            Toast.makeText(
                context,
                "桌面歌词已开启，退出 CatMusic 后显示",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context,
                "需要允许 CatMusic 显示在其他应用上层",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    val toggleDesktopLyrics: () -> Unit = {
        if (desktopLyricsEnabled) {
            viewModel.setDesktopLyricsEnabled(false)
        } else if (Settings.canDrawOverlays(context)) {
            viewModel.setDesktopLyricsEnabled(true)
            Toast.makeText(
                context,
                "桌面歌词已开启，退出 CatMusic 后显示",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { overlayPermissionLauncher.launch(intent) }.onFailure {
                Toast.makeText(
                    context,
                    "当前系统无法打开悬浮窗权限设置",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

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

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.Online) onlineTabOpened = true
    }

    // Online previews now survive tab changes. If the user starts local
    // playback from the library, a playlist, a headset or the media
    // notification, explicitly stop the online page so both never sound at
    // once. The online page itself still asks local playback to pause when a
    // preview starts (the opposite direction).
    LaunchedEffect(player.isPlaying) {
        if (player.isPlaying) OnlineAudioBus.requestPause()
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

    // Ask once on entry, not only when the first song starts. This is what lets
    // Vivo OriginOS (and other Android 13+ skins) surface CatMusic's media
    // session in the notification shade / Dynamic Island from the very first
    // playback. The short delay gives the launch transition time to settle so
    // the system dialog does not fight the splash screen.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            delay(1_100L)
            requestNotificationPermission()
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
        // Always synchronise the selected preset into the native scene. The
        // previous logic applied only a cover while a track was playing and
        // never cleared the old METABALL style, so switching away from
        // 熔岩流体 appeared to do nothing.
        val styleNeedsSync = !backdrop.matchesHome(
            homeBackgroundStyle,
            customHomeBackgroundFile
        )
        if (styleNeedsSync) {
            backdrop.setHome(homeBackgroundStyle, customHomeBackgroundFile)
        }
        // The MetaBall scene is an always-on background: unlike the other
        // presets it is not replaced by album colours while music is playing.
        if (track != null && homeBackgroundStyle != HomeBackgroundStyle.METABALL) {
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
        } else if (!styleNeedsSync) {
            // Keep the normal no-track / METABALL path (and clear a cover left
            // over from the previous song) when the preset has not changed.
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

    val motionNanos = rememberAdaptiveMotionNanos(lyricFrameRate, player.isPlaying)
    LaunchedEffect(Unit) { AdaptiveMotion.pulse() }
    LaunchedEffect(player.isPlaying, currentTrack?.id) { AdaptiveMotion.pulse() }
    LaunchedEffect(lyricFrameRate, player.isPlaying) {
        (GlassBackdrop.current() as? GlassBackdropView)
            ?.setMotionProfile(lyricFrameRate, player.isPlaying)
    }

    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = importActive) { viewModel.cancelImport() }

    CompositionLocalProvider(
        LocalAdaptiveMotionNanos provides motionNanos,
        LocalMotionFrameRate provides lyricFrameRate
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        AdaptiveMotion.pulse()
                    }
                }
            }
    ) {
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
                                    if (tab == AppTab.Online) onlineTabOpened = true
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
                    // Keep only horizontal safe-drawing padding. Reserving the
                    // full top cutout here made the home page look like it had
                    // a large black "forehead" above the header. The status bar
                    // is hidden, so the content can use the edge-to-edge space;
                    // the header already keeps its controls near the sides.
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
                            layoutStyle = libraryLayoutStyle,
                            onChangeLayoutStyle = viewModel::setLibraryLayoutStyle,
                            onTrackAction = onTrackAction,
                            onShowImport = { showImportSheet = true },
                            onDeleteTrack = { pendingDeleteTrack = it },
                            onOpenTrackInfo = openTrackInfo,
                            onAddToPlaylist = { addToPlaylistTrackId = it.id }
                        )

                        AppTab.Online -> Box(Modifier.fillMaxSize())

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
                            lyricTranslationMode = lyricTranslationMode,
                            onSelectLyricTranslationMode = viewModel::setLyricTranslationMode,
                            desktopLyricsEnabled = desktopLyricsEnabled,
                            onToggleDesktopLyrics = viewModel::setDesktopLyricsEnabled,
                            onPickMixed = { openMixedFilesLauncher.launch(arrayOf("*/*")) },
                            onPickFiles = { openFilesLauncher.launch(arrayOf("audio/*")) },
                            onPickFolder = { openFolderLauncher.launch(null) },
                            onClearLibrary = viewModel::clearLibrary,
                            onResetStats = viewModel::resetStatistics
                        )
                    }
                }

                // Keep the MusicDL WebView mounted after the first visit and
                // simply park it at zero height when another tab is active.
                // The page, search history and resolved result list survive, so
                // returning to 在线 is instant instead of rebuilding the whole
                // WebView. The WebView itself is paused and its audio stopped
                // while parked.
                if (onlineTabOpened) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = 900.dp)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            // Park the full-size WebView outside the viewport
                            // rather than measuring it at zero height. Its
                            // viewport therefore stays stable across tab
                            // switches; hiding is handled by the screen's
                            // active flag (visibility + onPause).
                            .offset(
                                y = if (selectedTab == AppTab.Online) 0.dp else 3000.dp
                            )
                    ) {
                        OnlineMusicScreen(
                            modifier = Modifier.fillMaxSize(),
                            active = selectedTab == AppTab.Online,
                            onExit = { selectedTab = AppTab.Library },
                            onOnlinePlaybackStarted = viewModel::pausePlayback
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
                    translationMode = lyricTranslationMode,
                    fadeInMs = fadeInMs,
                    fadeOutMs = fadeOutMs,
                    onFadeInChange = viewModel::setFadeInMs,
                    onFadeOutChange = viewModel::setFadeOutMs,
                    desktopLyricsEnabled = desktopLyricsEnabled,
                    onToggleDesktopLyrics = toggleDesktopLyrics,
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
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                )
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
