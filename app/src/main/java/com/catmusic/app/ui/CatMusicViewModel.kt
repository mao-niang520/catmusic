package com.catmusic.app.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.catmusic.app.data.AudioFileStore
import com.catmusic.app.data.AudioVisualLevel
import com.catmusic.app.data.ArtworkTransformer
import com.catmusic.app.data.HomeBackgroundStore
import com.catmusic.app.data.HomeBackgroundStyle
import com.catmusic.app.data.ImportState
import com.catmusic.app.data.LyricFrameRate
import com.catmusic.app.data.LyricsImporter
import com.catmusic.app.data.MixedImporter
import com.catmusic.app.data.MusicImporter
import com.catmusic.app.data.MAX_QUICK_LINKS
import com.catmusic.app.data.MusicRepository
import com.catmusic.app.data.Playlist
import com.catmusic.app.data.QuickLink
import com.catmusic.app.data.Track
import com.catmusic.app.playback.PlaybackService
import com.catmusic.app.playback.SleepTimerState
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatMusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository.get(application)

    val tracks: StateFlow<List<Track>> = repository.tracks
    val stats = repository.stats
    val playlists: StateFlow<List<Playlist>> = repository.playlists
    val quickLinks: StateFlow<List<QuickLink>> = repository.quickLinks
    val homeBackgroundStyle: StateFlow<HomeBackgroundStyle> =
        repository.homeBackgroundStyle
    val customHomeBackground: StateFlow<String?> = repository.customHomeBackground
    val audioReactiveEnabled: StateFlow<Boolean> = repository.audioReactiveEnabled
    val audioVisualLevel: StateFlow<AudioVisualLevel> = repository.audioVisualLevel
    val lyricFrameRate: StateFlow<LyricFrameRate> = repository.lyricFrameRate
    val sleepTimerRemainingMs: StateFlow<Long> = SleepTimerState.remainingMs
    val sleepTimerTotalMs: StateFlow<Long> = SleepTimerState.totalMs

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 6)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var importJob: Job? = null

    private val controllerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            _messages.tryEmit("这个文件暂时无法播放，已尝试跳到下一首")
        }
    }

    init {
        connectPlayer()
        migrateLegacyAudioCopies()
    }

    /**
     * Versions before 1.2 used external document URIs directly. Copy any such
     * legacy entries into app-private storage so deleting the original file
     * cannot break the library. New imports are copied during import already.
     */
    private fun migrateLegacyAudioCopies() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val legacy = repository.tracks.value.filter { track ->
                track.uri.isNotBlank() && !AudioFileStore.isManagedUri(app, track.uri)
            }
            if (legacy.isEmpty()) return@launch
            val migrated = withContext(Dispatchers.IO) {
                var count = 0
                legacy.forEach { track ->
                    val source = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return@forEach
                    val displayName = AudioFileStore.queryDisplayName(app, source) ?: track.title
                    val mimeType = runCatching { app.contentResolver.getType(source) }.getOrNull()
                        ?: track.mimeType
                    val copy = AudioFileStore.copyToManaged(
                        context = app,
                        trackId = track.id,
                        sourceUri = source,
                        displayName = displayName,
                        mimeType = mimeType
                    ) ?: return@forEach
                    if (repository.tracks.value.any { it.id == track.id }) {
                        repository.updateTrackUri(
                            trackId = track.id,
                            uri = Uri.fromFile(copy).toString(),
                            sourceUri = track.uri,
                            sourceName = displayName
                        )
                        count++
                    } else {
                        copy.delete()
                    }
                }
                count
            }
            if (migrated > 0) {
                _messages.tryEmit("已将 $migrated 首旧歌曲保存到软件内，删除原文件不再影响播放")
            }
        }
    }

    private fun connectPlayer() {
        val app = getApplication<Application>()
        val token = SessionToken(
            app,
            ComponentName(app, PlaybackService::class.java)
        )
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }
                .onSuccess { controller ->
                    controller.addListener(controllerListener)
                    _controller.value = controller
                }
                .onFailure { error ->
                    Log.e(TAG, "Could not connect to playback service", error)
                    _messages.tryEmit("播放器连接失败，请重新打开 CatMusic")
                }
        }, ContextCompat.getMainExecutor(app))
    }

    fun playTrack(track: Track, queue: List<Track>) {
        val player = _controller.value
        if (player == null) {
            _messages.tryEmit("播放器正在启动，请稍后再试")
            return
        }
        if (player.currentMediaItem?.mediaId == track.id &&
            player.playbackState != Player.STATE_IDLE
        ) {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekToDefaultPosition()
            }
            player.play()
            return
        }
        val queueToUse = if (queue.any { it.id == track.id }) queue else listOf(track)
        val index = queueToUse.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.setMediaItems(queueToUse.map(::toMediaItem))
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
    }

    fun playQueueFromStart(track: Track, queue: List<Track>) {
        val player = _controller.value
        if (player == null) {
            _messages.tryEmit("播放器正在启动，请稍后再试")
            return
        }
        val queueToUse = if (queue.any { it.id == track.id }) queue else listOf(track)
        val index = queueToUse.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.setMediaItems(queueToUse.map(::toMediaItem))
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
    }

    fun togglePlayback() {
        val player = _controller.value ?: return
        if (player.currentMediaItem == null) return
        // The service fades the player's own volume before the actual pause
        // and fades back in on resume, so the UI toggle and the sleep timer
        // use the same audio path.
        sendSessionCommand(SleepTimerState.ACTION_FADE_TOGGLE)
    }

    /**
     * Pause local playback only when the online preview actually starts.
     * Merely opening the Online tab must not interrupt the local queue.
     */
    fun pausePlayback() {
        val player = _controller.value ?: return
        if (player.currentMediaItem == null || !player.isPlaying) return
        sendSessionCommand(SleepTimerState.ACTION_FADE_TOGGLE)
    }

    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            clearSleepTimer()
            return
        }
        val args = Bundle().apply {
            putInt(SleepTimerState.ARG_SLEEP_MINUTES, minutes)
        }
        sendSessionCommand(SleepTimerState.ACTION_SET_SLEEP_TIMER, args)
        _messages.tryEmit("$minutes 分钟后淡出并暂停")
    }

    fun clearSleepTimer() {
        sendSessionCommand(SleepTimerState.ACTION_CLEAR_SLEEP_TIMER)
        _messages.tryEmit("定时暂停已关闭")
    }

    private fun sendSessionCommand(action: String, args: Bundle = Bundle.EMPTY) {
        val player = _controller.value ?: return
        runCatching {
            player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        }.onFailure {
            Log.w(TAG, "Could not send session command $action", it)
        }
    }

    fun next() {
        _controller.value?.seekToNextMediaItem()
    }

    fun previous() {
        // A deliberate previous tap always changes track. Media3's helper
        // restarts the current item when it has been playing for a few
        // seconds; users expect "previous" to mean the previous song.
        _controller.value?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        _controller.value?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setLyricOffset(trackId: String, offsetMs: Long) {
        repository.setTrackLyricOffset(trackId, offsetMs)
    }

    fun toggleShuffle() {
        val player = _controller.value ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val player = _controller.value ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        _messages.tryEmit(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> "循环模式：列表循环"
                Player.REPEAT_MODE_ONE -> "循环模式：单曲循环"
                else -> "循环模式：已关闭"
            }
        )
    }

    fun importDocumentUris(uris: List<Uri>) {
        startImport(uris)
    }

    /**
     * One picker selection may contain audio, .lrc/.txt lyrics and cover
     * images. They are split by [MixedImporter] and paired by file name.
     */
    fun importMixedDocumentUris(uris: List<Uri>) {
        startImport(uris)
    }

    private fun startImport(uris: List<Uri>) {
        if (uris.isEmpty() || importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _importState.value = ImportState.Importing(0, uris.size, 0)
            try {
                val outcome = MixedImporter.importMixed(
                    context = getApplication(),
                    rawUris = uris,
                    skipTrackIds = repository.tracks.value.mapTo(HashSet()) { it.id }
                ) { processed, total, parsed ->
                    _importState.value = ImportState.Importing(processed, total, parsed)
                }
                if (outcome.audioCount == 0) {
                    _importState.value = ImportState.Failed(
                        "没有找到音频文件，歌词和封面需要和歌曲一起选择"
                    )
                    return@launch
                }
                finishImport(
                    importedTracks = outcome.tracks,
                    skipped = outcome.skipped,
                    failed = outcome.failed,
                    lyricsMatched = outcome.lyricsMatched,
                    coversMatched = outcome.coversMatched
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Import failed", error)
                _importState.value = ImportState.Failed("导入失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun importFolder(treeUri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _importState.value = ImportState.Scanning
            try {
                val uris = MusicImporter.collectTreeUris(
                    context = getApplication(),
                    treeUri = treeUri,
                    includeCompanions = true
                )
                if (uris.isEmpty()) {
                    _importState.value = ImportState.Finished(imported = 0, skipped = 0, failed = 0)
                    return@launch
                }
                _importState.value = ImportState.Importing(0, uris.size, 0)
                val outcome = MixedImporter.importMixed(
                    context = getApplication(),
                    rawUris = uris,
                    skipTrackIds = repository.tracks.value.mapTo(HashSet()) { it.id }
                ) { processed, total, parsed ->
                    _importState.value = ImportState.Importing(processed, total, parsed)
                }
                finishImport(
                    importedTracks = outcome.tracks,
                    skipped = outcome.skipped,
                    failed = outcome.failed,
                    lyricsMatched = outcome.lyricsMatched,
                    coversMatched = outcome.coversMatched
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Folder import failed", error)
                _importState.value = ImportState.Failed("导入失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun finishImport(
        importedTracks: List<Track>,
        skipped: Int = 0,
        failed: Int = 0,
        lyricsMatched: Int = 0,
        coversMatched: Int = 0
    ) {
        val added = repository.addTracks(importedTracks)
        val duplicates = (importedTracks.size - added).coerceAtLeast(0)
        _importState.value = ImportState.Finished(
            imported = added,
            skipped = skipped,
            failed = failed,
            duplicates = duplicates,
            lyricsMatched = lyricsMatched,
            coversMatched = coversMatched
        )
    }

    fun acknowledgeImportState() {
        _importState.value = ImportState.Idle
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        if (_importState.value !is ImportState.Idle) {
            _importState.value = ImportState.Idle
            _messages.tryEmit("已取消导入")
        }
    }

    fun removeTrack(track: Track) {
        _controller.value?.let { player ->
            if (player.currentMediaItem?.mediaId == track.id) {
                player.stop()
                player.clearMediaItems()
            } else {
                val indices = (0 until player.mediaItemCount).filter { index ->
                    player.getMediaItemAt(index).mediaId == track.id
                }
                indices.asReversed().forEach { index ->
                    player.removeMediaItem(index)
                }
            }
        }
        repository.removeTrack(track.id)
        _messages.tryEmit("已从软件中删除《${track.title}》，原文件不受影响")
    }

    fun clearLibrary() {
        _controller.value?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
        repository.clearLibrary()
        _messages.tryEmit("音乐库、歌单和软件内音频副本已清空")
    }

    fun resetStatistics() {
        repository.resetStatistics()
        _messages.tryEmit("听歌统计已归零")
    }

    fun updateTrackMetadata(track: Track, title: String, artist: String, album: String) {
        repository.updateTrackMetadata(track.id, title, artist, album)
        _messages.tryEmit("歌曲信息已保存")
    }

    fun createPlaylist(name: String) {
        val playlist = repository.createPlaylist(name) ?: return
        _messages.tryEmit("歌单「${playlist.name}」已创建")
    }

    fun renamePlaylist(playlistId: String, name: String) {
        if (repository.renamePlaylist(playlistId, name)) {
            _messages.tryEmit("歌单已重命名")
        }
    }

    fun deletePlaylist(playlistId: String) {
        repository.deletePlaylist(playlistId)
        _messages.tryEmit("歌单已删除，歌曲仍保留在音乐馆")
    }

    fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        val added = repository.addTracksToPlaylist(playlistId, trackIds)
        if (added > 0) {
            _messages.tryEmit("已添加 $added 首歌曲到歌单")
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        repository.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun moveTrackInPlaylist(playlistId: String, trackId: String, offset: Int) {
        repository.moveTrackInPlaylist(playlistId, trackId, offset)
    }

    fun setAudioReactiveEnabled(enabled: Boolean) {
        repository.setAudioReactiveEnabled(enabled)
    }

    fun setAudioVisualLevel(level: AudioVisualLevel) {
        repository.setAudioVisualLevel(level)
    }

    fun setLyricFrameRate(rate: LyricFrameRate) {
        repository.setLyricFrameRate(rate)
    }

    fun saveQuickLink(link: QuickLink) {
        if (!repository.saveQuickLink(link)) {
            _messages.tryEmit("最多只能保存 $MAX_QUICK_LINKS 个网页快捷方式")
        }
    }

    fun deleteQuickLink(id: String) {
        repository.deleteQuickLink(id)
    }

    fun setHomeBackgroundStyle(style: HomeBackgroundStyle) {
        repository.setHomeBackgroundStyle(style)
    }

    fun importCustomHomeBackground(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val old = repository.customHomeBackground.value
            val fileName = runCatching {
                HomeBackgroundStore.import(app, uri)
            }.getOrNull()
            if (fileName != null) {
                repository.setCustomHomeBackground(fileName)
                repository.setHomeBackgroundStyle(HomeBackgroundStyle.CUSTOM)
                if (!old.isNullOrBlank() && old != fileName) {
                    HomeBackgroundStore.delete(app, old)
                }
                _messages.tryEmit("主界面背景已更新")
            } else {
                _messages.tryEmit("背景图片导入失败，请换一张图片试试")
            }
        }
    }

    fun clearCustomHomeBackground() {
        val app = getApplication<Application>()
        val old = repository.customHomeBackground.value
        repository.setCustomHomeBackground(null)
        repository.setHomeBackgroundStyle(HomeBackgroundStyle.AURORA)
        HomeBackgroundStore.delete(app, old)
        _messages.tryEmit("已恢复默认极光背景")
    }

    fun setCustomArtwork(trackId: String, uri: Uri) {
        viewModelScope.launch {
            val oldArtwork = repository.tracks.value.firstOrNull { it.id == trackId }?.artworkFile
            val fileName = runCatching {
                ArtworkTransformer.import(getApplication(), trackId, uri)
            }.getOrNull()
            if (fileName != null) {
                repository.updateTrackArtwork(trackId, fileName)
                if (!oldArtwork.isNullOrBlank() &&
                    oldArtwork != fileName &&
                    oldArtwork != "$trackId.jpg"
                ) {
                    repository.artworkFile(oldArtwork)?.delete()
                }
                _messages.tryEmit("专辑封面已更新")
            } else {
                _messages.tryEmit("封面导入失败，请换一张图片试试")
            }
        }
    }

    fun resetArtwork(track: Track) {
        val defaultArtwork = repository.defaultArtworkFileName(track.id)
        repository.updateTrackArtwork(track.id, defaultArtwork)
        if (!track.artworkFile.isNullOrBlank() &&
            track.artworkFile != defaultArtwork &&
            track.artworkFile != "${track.id}.jpg"
        ) {
            repository.artworkFile(track.artworkFile)?.delete()
        }
        _messages.tryEmit(
            if (defaultArtwork != null) "已恢复内嵌封面" else "已恢复默认渐变封面"
        )
    }

    fun importLyrics(trackId: String, uri: Uri) {
        viewModelScope.launch {
            val oldLyrics = repository.tracks.value.firstOrNull { it.id == trackId }?.lyricsFile
            val fileName = runCatching {
                LyricsImporter.import(getApplication(), trackId, uri)
            }.getOrNull()
            if (fileName != null) {
                repository.updateTrackLyrics(trackId, fileName)
                if (!oldLyrics.isNullOrBlank() && oldLyrics != fileName) {
                    repository.lyricsFile(oldLyrics)?.delete()
                }
                _messages.tryEmit("歌词已导入，轻点“歌词”胶囊即可查看")
            } else {
                _messages.tryEmit("歌词读取失败，请选择 LRC 或 UTF-8/GBK 文本")
            }
        }
    }

    private fun toMediaItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply {
                repository.artworkFile(track.artworkFile)?.let {
                    setArtworkUri(Uri.fromFile(it))
                }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun onCleared() {
        _controller.value?.let { player ->
            player.removeListener(controllerListener)
            _controller.value = null
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "CatMusicViewModel"
    }
}
