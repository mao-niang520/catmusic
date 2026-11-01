package com.catmusic.app.data

data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkFile: String? = null,
    val mimeType: String? = null,
    val addedAt: Long = 0L,
    val lyricsFile: String? = null,
    val lyricOffsetMs: Long = 0L,
    val sourceUri: String? = null,
    val sourceName: String? = null
)

data class TrackStats(
    val listenedMs: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L
)

data class ListeningStats(
    val totalMs: Long = 0L,
    val tracks: Map<String, TrackStats> = emptyMap(),
    val dailyMs: Map<String, Long> = emptyMap()
)

const val MAX_QUICK_LINKS = 10

/**
 * A user-defined shortcut that hands an http(s) URL to an external browser.
 * CatMusic itself never downloads the page; [browserPackage] is only used as
 * an explicit package hint for ACTION_VIEW.
 */
data class QuickLink(
    val id: String,
    val title: String,
    val url: String,
    val browserPackage: String? = null,
    val browserLabel: String? = null
)

sealed interface ImportState {
    data object Idle : ImportState
    data object Scanning : ImportState
    data class Importing(
        val processed: Int,
        val total: Int,
        val parsed: Int
    ) : ImportState

    data class Finished(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val duplicates: Int = 0,
        val lyricsMatched: Int = 0,
        val coversMatched: Int = 0
    ) : ImportState

    data class Failed(val message: String) : ImportState
}

data class ImportOutcome(
    val tracks: List<Track>,
    val skipped: Int,
    val failed: Int
)
