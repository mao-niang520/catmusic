package com.catmusic.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Single source of truth for the local library and listening statistics.
 * All mutations are serialized so the playback service and UI can safely share it.
 */
class MusicRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _stats = MutableStateFlow(ListeningStats())
    val stats: StateFlow<ListeningStats> = _stats.asStateFlow()

    private val _audioReactiveEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUDIO_REACTIVE, true)
    )
    val audioReactiveEnabled: StateFlow<Boolean> = _audioReactiveEnabled.asStateFlow()

    private val _audioVisualLevel = MutableStateFlow(
        AudioVisualLevel.fromKey(prefs.getString(KEY_AUDIO_LEVEL, null))
    )
    val audioVisualLevel: StateFlow<AudioVisualLevel> = _audioVisualLevel.asStateFlow()

    private val _lyricFrameRate = MutableStateFlow(
        LyricFrameRate.fromKey(prefs.getString(KEY_LYRIC_FPS, null))
    )
    val lyricFrameRate: StateFlow<LyricFrameRate> = _lyricFrameRate.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _quickLinks = MutableStateFlow<List<QuickLink>>(emptyList())
    val quickLinks: StateFlow<List<QuickLink>> = _quickLinks.asStateFlow()

    private val _homeBackgroundStyle = MutableStateFlow(
        HomeBackgroundStyle.fromKey(prefs.getString(KEY_HOME_BACKGROUND, null))
    )
    val homeBackgroundStyle: StateFlow<HomeBackgroundStyle> =
        _homeBackgroundStyle.asStateFlow()

    private val _customHomeBackground = MutableStateFlow(
        prefs.getString(KEY_CUSTOM_HOME_BACKGROUND, null)
            ?.takeIf { it.isNotBlank() }
    )
    val customHomeBackground: StateFlow<String?> =
        _customHomeBackground.asStateFlow()

    private var lastStatsPersistAt = 0L

    init {
        _tracks.value = loadTracks()
        _stats.value = loadStats()
        _playlists.value = loadPlaylists()
        _quickLinks.value = loadQuickLinks()
    }

    fun artworkDir(): File {
        return File(appContext.filesDir, ARTWORK_DIR).apply { if (!exists()) mkdirs() }
    }

    fun artworkFile(fileName: String?): File? {
        if (fileName.isNullOrBlank()) return null
        return File(artworkDir(), fileName).takeIf { it.exists() }
    }

    fun lyricsDir(): File {
        return File(appContext.filesDir, LYRICS_DIR).apply { if (!exists()) mkdirs() }
    }

    fun lyricsFile(fileName: String?): File? {
        if (fileName.isNullOrBlank()) return null
        return File(lyricsDir(), fileName).takeIf { it.exists() }
    }

    fun defaultArtworkFileName(trackId: String): String? {
        return File(artworkDir(), "$trackId.jpg").takeIf { it.exists() }?.name
    }

    fun setAudioReactiveEnabled(enabled: Boolean) {
        _audioReactiveEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUDIO_REACTIVE, enabled).apply()
    }

    fun setAudioVisualLevel(level: AudioVisualLevel) {
        _audioVisualLevel.value = level
        prefs.edit().putString(KEY_AUDIO_LEVEL, level.key).apply()
    }

    fun setLyricFrameRate(rate: LyricFrameRate) {
        _lyricFrameRate.value = rate
        prefs.edit().putString(KEY_LYRIC_FPS, rate.key).apply()
    }

    /**
     * Adds or updates a shortcut. Returns false when the ten-slot limit is
     * already reached for a new entry. The URL itself is validated by the UI;
     * this trims and caps the stored fields defensively.
     */
    fun saveQuickLink(link: QuickLink): Boolean {
        val clean = link.copy(
            title = link.title.trim().take(64),
            url = link.url.trim().take(2048),
            browserPackage = link.browserPackage
                ?.trim()
                ?.take(200)
                ?.takeIf { it.isNotEmpty() },
            browserLabel = link.browserLabel
                ?.trim()
                ?.take(64)
                ?.takeIf { it.isNotEmpty() }
        )
        if (clean.id.isBlank() || clean.url.isBlank()) return false
        synchronized(lock) {
            val current = _quickLinks.value
            val existing = current.indexOfFirst { it.id == clean.id }
            val updated = if (existing >= 0) {
                current.toMutableList().also { it[existing] = clean }
            } else {
                if (current.size >= MAX_QUICK_LINKS) return false
                current + clean
            }
            _quickLinks.value = updated
            prefs.edit().putString(KEY_QUICK_LINKS, quickLinksJson(updated)).apply()
        }
        return true
    }

    fun deleteQuickLink(id: String) {
        synchronized(lock) {
            val updated = _quickLinks.value.filterNot { it.id == id }
            if (updated.size == _quickLinks.value.size) return
            _quickLinks.value = updated
            prefs.edit().putString(KEY_QUICK_LINKS, quickLinksJson(updated)).apply()
        }
    }

    fun setHomeBackgroundStyle(style: HomeBackgroundStyle) {
        _homeBackgroundStyle.value = style
        prefs.edit().putString(KEY_HOME_BACKGROUND, style.key).apply()
    }

    fun setCustomHomeBackground(fileName: String?) {
        _customHomeBackground.value = fileName?.takeIf { it.isNotBlank() }
        prefs.edit()
            .putString(KEY_CUSTOM_HOME_BACKGROUND, _customHomeBackground.value)
            .apply()
    }

    fun updateTrackArtwork(trackId: String, artworkFile: String?) {
        updateTrack(trackId) { track -> track.copy(artworkFile = artworkFile) }
    }

    fun updateTrackLyrics(trackId: String, lyricsFile: String?) {
        updateTrack(trackId) { track -> track.copy(lyricsFile = lyricsFile) }
    }

    /** Positive values delay the lyrics; negative values make them appear earlier. */
    fun setTrackLyricOffset(trackId: String, offsetMs: Long) {
        val safeOffset = offsetMs.coerceIn(-LYRIC_OFFSET_LIMIT_MS, LYRIC_OFFSET_LIMIT_MS)
        updateTrack(trackId) { track ->
            if (track.lyricOffsetMs == safeOffset) track
            else track.copy(lyricOffsetMs = safeOffset)
        }
    }

    fun updateTrackMetadata(trackId: String, title: String, artist: String, album: String) {
        updateTrack(trackId) { track ->
            track.copy(
                title = title.trim().ifBlank { "未知音频" },
                artist = artist.trim().ifBlank { "未知艺术家" },
                album = album.trim().ifBlank { "未知专辑" }
            )
        }
    }

    fun updateTrackUri(
        trackId: String,
        uri: String,
        sourceUri: String? = null,
        sourceName: String? = null
    ) {
        updateTrack(trackId) { track ->
            track.copy(
                uri = uri,
                sourceUri = track.sourceUri ?: sourceUri,
                sourceName = track.sourceName ?: sourceName
            )
        }
    }

    fun createPlaylist(name: String): Playlist? {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return null
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAt = now,
            updatedAt = now
        )
        synchronized(lock) {
            _playlists.value = (_playlists.value + playlist)
                .sortedByDescending { it.updatedAt }
            savePlaylistsLocked()
        }
        return playlist
    }

    fun renamePlaylist(playlistId: String, name: String): Boolean {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return false
        synchronized(lock) {
            var changed = false
            val updated = _playlists.value.map { playlist ->
                if (playlist.id == playlistId && playlist.name != cleanName) {
                    changed = true
                    playlist.copy(name = cleanName, updatedAt = System.currentTimeMillis())
                } else {
                    playlist
                }
            }
            if (changed) {
                _playlists.value = updated.sortedByDescending { it.updatedAt }
                savePlaylistsLocked()
            }
            return changed
        }
    }

    fun deletePlaylist(playlistId: String) {
        synchronized(lock) {
            val updated = _playlists.value.filterNot { it.id == playlistId }
            if (updated.size != _playlists.value.size) {
                _playlists.value = updated
                savePlaylistsLocked()
            }
        }
    }

    fun addTracksToPlaylist(playlistId: String, trackIds: List<String>): Int {
        synchronized(lock) {
            val existingTrackIds = _tracks.value.mapTo(HashSet()) { it.id }
            var added = 0
            val updated = _playlists.value.map { playlist ->
                if (playlist.id != playlistId) return@map playlist
                val newIds = trackIds
                    .filter { it in existingTrackIds && it !in playlist.trackIds }
                    .distinct()
                if (newIds.isEmpty()) {
                    playlist
                } else {
                    added = newIds.size
                    playlist.copy(
                        trackIds = playlist.trackIds + newIds,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
            if (added > 0) {
                _playlists.value = updated.sortedByDescending { it.updatedAt }
                savePlaylistsLocked()
            }
            return added
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String): Boolean {
        synchronized(lock) {
            var changed = false
            val updated = _playlists.value.map { playlist ->
                if (playlist.id == playlistId && trackId in playlist.trackIds) {
                    changed = true
                    playlist.copy(
                        trackIds = playlist.trackIds - trackId,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    playlist
                }
            }
            if (changed) {
                _playlists.value = updated
                savePlaylistsLocked()
            }
            return changed
        }
    }

    fun moveTrackInPlaylist(playlistId: String, trackId: String, offset: Int): Boolean {
        if (offset == 0) return false
        synchronized(lock) {
            var changed = false
            val updated = _playlists.value.map { playlist ->
                if (playlist.id != playlistId) return@map playlist
                val index = playlist.trackIds.indexOf(trackId)
                val target = index + offset
                if (index < 0 || target !in playlist.trackIds.indices) {
                    playlist
                } else {
                    changed = true
                    val ids = playlist.trackIds.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(target, moved)
                    playlist.copy(
                        trackIds = ids,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
            if (changed) {
                _playlists.value = updated.sortedByDescending { it.updatedAt }
                savePlaylistsLocked()
            }
            return changed
        }
    }

    private fun updateTrack(trackId: String, transform: (Track) -> Track) {
        synchronized(lock) {
            var changed = false
            val updated = _tracks.value.map { track ->
                if (track.id == trackId) {
                    val next = transform(track)
                    if (next != track) {
                        changed = true
                        next
                    } else {
                        track
                    }
                } else {
                    track
                }
            }
            if (changed) {
                _tracks.value = updated
                saveTracksLocked(updated)
            }
        }
    }

    fun addTracks(items: List<Track>): Int {
        synchronized(lock) {
            val merged = _tracks.value.associateBy { it.id }.toMutableMap()
            var added = 0
            items.forEach { track ->
                if (!merged.containsKey(track.id)) {
                    merged[track.id] = track
                    added++
                }
            }
            if (added > 0) {
                val sorted = merged.values.sortedWith(
                    compareByDescending<Track> { it.addedAt }
                        .thenBy { it.title.lowercase() }
                )
                _tracks.value = sorted
                saveTracksLocked(sorted)
            }
            return added
        }
    }

    fun removeTrack(trackId: String) {
        var removed: Track? = null
        synchronized(lock) {
            removed = _tracks.value.firstOrNull { it.id == trackId }
            val updated = _tracks.value.filterNot { it.id == trackId }
            if (updated.size != _tracks.value.size) {
                _tracks.value = updated
                saveTracksLocked(updated)
            }
            val updatedPlaylists = _playlists.value.map { playlist ->
                if (trackId in playlist.trackIds) {
                    playlist.copy(
                        trackIds = playlist.trackIds - trackId,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    playlist
                }
            }
            if (updatedPlaylists != _playlists.value) {
                _playlists.value = updatedPlaylists
                savePlaylistsLocked()
            }
        }
        removed?.let { deleteTrackAssets(it) }
    }

    fun clearLibrary() {
        synchronized(lock) {
            _tracks.value = emptyList()
            _playlists.value = emptyList()
            saveTracksLocked(emptyList())
            savePlaylistsLocked()
        }
        AudioFileStore.deleteAllAudio(appContext)
        runCatching {
            artworkDir().listFiles()?.forEach { it.delete() }
            lyricsDir().listFiles()?.forEach { it.delete() }
        }
    }

    private fun deleteTrackAssets(track: Track) {
        AudioFileStore.deleteManagedAudio(appContext, track.uri)
        deleteFilesWithPrefix(artworkDir(), track.id)
        deleteFilesWithPrefix(lyricsDir(), track.id)
    }

    private fun deleteFilesWithPrefix(directory: File, prefix: String) {
        runCatching {
            directory.listFiles()?.forEach { file ->
                if (file.name.startsWith(prefix)) file.delete()
            }
        }
    }

    fun updateTrackDuration(trackId: String, durationMs: Long) {
        if (durationMs <= 0L) return
        synchronized(lock) {
            var changed = false
            val updated = _tracks.value.map { track ->
                if (track.id == trackId && (track.durationMs <= 0L ||
                        kotlin.math.abs(track.durationMs - durationMs) > 2000L)
                ) {
                    changed = true
                    track.copy(durationMs = durationMs)
                } else {
                    track
                }
            }
            if (changed) {
                _tracks.value = updated
                saveTracksLocked(updated)
            }
        }
    }

    fun recordPlay(trackId: String) {
        if (trackId.isBlank()) return
        synchronized(lock) {
            val current = _stats.value
            val old = current.tracks[trackId] ?: TrackStats()
            val next = old.copy(
                playCount = old.playCount + 1,
                lastPlayedAt = System.currentTimeMillis()
            )
            _stats.value = current.copy(tracks = current.tracks + (trackId to next))
            persistStatsLocked()
            lastStatsPersistAt = SystemClock.elapsedRealtime()
        }
    }

    fun addListeningTime(trackId: String, deltaMs: Long) {
        if (trackId.isBlank() || deltaMs <= 0L) return
        val safeDelta = deltaMs.coerceAtMost(60_000L)
        val today = LocalDate.now().toString()
        synchronized(lock) {
            val current = _stats.value
            val old = current.tracks[trackId] ?: TrackStats()
            val updatedTrack = old.copy(listenedMs = old.listenedMs + safeDelta)
            val updatedDaily = current.dailyMs.toMutableMap().apply {
                put(today, (get(today) ?: 0L) + safeDelta)
            }
            _stats.value = current.copy(
                totalMs = current.totalMs + safeDelta,
                tracks = current.tracks + (trackId to updatedTrack),
                dailyMs = updatedDaily
            )
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatsPersistAt >= STATS_PERSIST_INTERVAL_MS) {
                persistStatsLocked()
                lastStatsPersistAt = now
            }
        }
    }

    fun flushStats() {
        synchronized(lock) {
            persistStatsLocked()
            lastStatsPersistAt = SystemClock.elapsedRealtime()
        }
    }

    fun resetStatistics() {
        synchronized(lock) {
            _stats.value = ListeningStats()
            persistStatsLocked()
            lastStatsPersistAt = SystemClock.elapsedRealtime()
        }
    }

    private fun saveTracksLocked(tracks: List<Track>) {
        try {
            val array = JSONArray()
            tracks.forEach { track ->
                array.put(
                    JSONObject()
                        .put("id", track.id)
                        .put("uri", track.uri)
                        .put("title", track.title)
                        .put("artist", track.artist)
                        .put("album", track.album)
                        .put("durationMs", track.durationMs)
                        .put("artworkFile", track.artworkFile ?: JSONObject.NULL)
                        .put("mimeType", track.mimeType ?: JSONObject.NULL)
                        .put("addedAt", track.addedAt)
                        .put("lyricsFile", track.lyricsFile ?: JSONObject.NULL)
                        .put("lyricOffsetMs", track.lyricOffsetMs)
                        .put("sourceUri", track.sourceUri ?: JSONObject.NULL)
                        .put("sourceName", track.sourceName ?: JSONObject.NULL)
                )
            }
            prefs.edit().putString(KEY_TRACKS, array.toString()).apply()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist library", error)
        }
    }

    private fun loadTracks(): List<Track> {
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val uri = item.optString("uri")
                    if (id.isBlank() || uri.isBlank()) continue
                    add(
                        Track(
                            id = id,
                            uri = uri,
                            title = item.optString("title", "未知音频"),
                            artist = item.optString("artist", "未知艺术家"),
                            album = item.optString("album", "未知专辑"),
                            durationMs = item.optLong("durationMs", 0L),
                            artworkFile = item.optString("artworkFile")
                                .takeIf { it.isNotBlank() && it != "null" },
                            mimeType = item.optString("mimeType")
                                .takeIf { it.isNotBlank() && it != "null" },
                            addedAt = item.optLong("addedAt", 0L),
                            lyricsFile = item.optString("lyricsFile")
                                .takeIf { it.isNotBlank() && it != "null" },
                            lyricOffsetMs = item.optLong("lyricOffsetMs", 0L)
                                .coerceIn(-LYRIC_OFFSET_LIMIT_MS, LYRIC_OFFSET_LIMIT_MS),
                            sourceUri = item.optString("sourceUri")
                                .takeIf { it.isNotBlank() && it != "null" },
                            sourceName = item.optString("sourceName")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }.sortedWith(compareByDescending<Track> { it.addedAt }.thenBy { it.title })
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load library", error)
            emptyList()
        }
    }

    private fun persistStatsLocked() {
        try {
            val stats = _stats.value
            val tracksJson = JSONObject()
            stats.tracks.forEach { (id, value) ->
                tracksJson.put(
                    id,
                    JSONObject()
                        .put("listenedMs", value.listenedMs)
                        .put("playCount", value.playCount)
                        .put("lastPlayedAt", value.lastPlayedAt)
                )
            }
            val dailyJson = JSONObject()
            stats.dailyMs.forEach { (day, value) -> dailyJson.put(day, value) }
            val root = JSONObject()
                .put("totalMs", stats.totalMs)
                .put("tracks", tracksJson)
                .put("dailyMs", dailyJson)
            prefs.edit().putString(KEY_STATS, root.toString()).apply()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist statistics", error)
        }
    }

    private fun savePlaylistsLocked() {
        try {
            val array = JSONArray()
            _playlists.value.forEach { playlist ->
                val ids = JSONArray()
                playlist.trackIds.forEach { trackId -> ids.put(trackId) }
                array.put(
                    JSONObject()
                        .put("id", playlist.id)
                        .put("name", playlist.name)
                        .put("trackIds", ids)
                        .put("createdAt", playlist.createdAt)
                        .put("updatedAt", playlist.updatedAt)
                )
            }
            prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist playlists", error)
        }
    }

    private fun loadPlaylists(): List<Playlist> {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isBlank() || name.isBlank()) continue
                    val idsArray = item.optJSONArray("trackIds")
                    val ids = buildList {
                        if (idsArray != null) {
                            for (idIndex in 0 until idsArray.length()) {
                                idsArray.optString(idIndex)
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }
                    }
                    add(
                        Playlist(
                            id = id,
                            name = name,
                            trackIds = ids.distinct(),
                            createdAt = item.optLong("createdAt", 0L),
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.updatedAt }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load playlists", error)
            emptyList()
        }
    }

    private fun quickLinksJson(links: List<QuickLink>): String {
        val array = JSONArray()
        links.forEach { link ->
            array.put(
                JSONObject()
                    .put("id", link.id)
                    .put("title", link.title)
                    .put("url", link.url)
                    .put("browserPackage", link.browserPackage ?: JSONObject.NULL)
                    .put("browserLabel", link.browserLabel ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    private fun loadQuickLinks(): List<QuickLink> {
        val raw = prefs.getString(KEY_QUICK_LINKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    if (size >= MAX_QUICK_LINKS) break
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val url = item.optString("url").trim()
                    if (id.isBlank() || url.isBlank()) continue
                    // Uri-style URLs may legally contain spaces or non-ASCII
                    // path segments, which java.net.URI rejects. Only the
                    // leading scheme matters for persistence.
                    val scheme = url.substringBefore(':', "")
                        .lowercase()
                    if (scheme != "http" && scheme != "https") continue
                    add(
                        QuickLink(
                            id = id,
                            title = item.optString("title").trim()
                                .ifBlank { url },
                            url = url,
                            browserPackage = item.optString("browserPackage")
                                .takeIf { it.isNotBlank() && it != "null" },
                            browserLabel = item.optString("browserLabel")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load quick links", error)
            emptyList()
        }
    }

    private fun loadStats(): ListeningStats {
        val raw = prefs.getString(KEY_STATS, null) ?: return ListeningStats()
        return try {
            val root = JSONObject(raw)
            val tracksJson = root.optJSONObject("tracks")
            val tracks = buildMap {
                tracksJson?.keys()?.forEach { id ->
                    val item = tracksJson.optJSONObject(id) ?: return@forEach
                    put(
                        id,
                        TrackStats(
                            listenedMs = item.optLong("listenedMs", 0L),
                            playCount = item.optInt("playCount", 0),
                            lastPlayedAt = item.optLong("lastPlayedAt", 0L)
                        )
                    )
                }
            }
            val dailyJson = root.optJSONObject("dailyMs")
            val daily = buildMap {
                dailyJson?.keys()?.forEach { day ->
                    put(day, dailyJson.optLong(day, 0L))
                }
            }
            ListeningStats(
                totalMs = root.optLong("totalMs", 0L),
                tracks = tracks,
                dailyMs = daily
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load statistics", error)
            ListeningStats()
        }
    }

    companion object {
        private const val TAG = "MusicRepository"
        private const val PREFS_NAME = "catmusic_library_v1"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_STATS = "stats"
        private const val KEY_AUDIO_REACTIVE = "audio_reactive_particles"
        private const val KEY_AUDIO_LEVEL = "audio_reactive_level"
        private const val KEY_LYRIC_FPS = "lyric_render_fps"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_QUICK_LINKS = "quick_links"
        private const val KEY_HOME_BACKGROUND = "home_background"
        private const val KEY_CUSTOM_HOME_BACKGROUND = "custom_home_background"
        private const val ARTWORK_DIR = "artwork"
        private const val LYRICS_DIR = "lyrics"
        private const val STATS_PERSIST_INTERVAL_MS = 4_000L
        private const val LYRIC_OFFSET_LIMIT_MS = 5_000L

        @Volatile
        private var instance: MusicRepository? = null

        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = MusicRepository(context)
                    }
                }
            }
        }

        fun get(context: Context): MusicRepository {
            return instance ?: synchronized(this) {
                instance ?: MusicRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
