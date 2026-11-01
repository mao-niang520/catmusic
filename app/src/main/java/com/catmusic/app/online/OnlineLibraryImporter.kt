package com.catmusic.app.online

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.catmusic.app.data.AudioFileStore
import com.catmusic.app.data.LyricsImporter
import com.catmusic.app.data.MusicRepository
import com.catmusic.app.data.Track
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Turns a fully downloaded online track into a first-class CatMusic library
 * entry. Audio, cover and LRC are kept in the same app-private directories as
 * imported files, so existing playback, deletion and backup semantics remain.
 */
object OnlineLibraryImporter {

    private const val TAG = "CatMusicOnline"
    private const val MAX_LYRICS_CHARS = 2_000_000
    private const val MAX_COVER_BYTES = 12L * 1024L * 1024L
    private val audioExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "webm", "mka"
    )

    @JvmStatic
    fun importDownloaded(
        context: Context,
        trackJson: String,
        audioFile: File,
        coverFile: File?,
        includeLyrics: Boolean
    ): String {
        return try {
            val json = JSONObject(trackJson)
            val source = json.optString("source").ifBlank { "online" }
            val sourceId = json.optString("id").ifBlank { json.optString("uiId") }
            if (sourceId.isBlank()) return errorJson("无法识别这首歌曲。")

            val trackId = UUID.nameUUIDFromBytes(
                "online|$source|$sourceId".toByteArray(Charsets.UTF_8)
            ).toString()
            val repository = MusicRepository.get(context)
            val existing = repository.tracks.value.firstOrNull { it.id == trackId }
            val lyricsText = if (includeLyrics) json.optString("lrc").trim() else ""
            val lyricsRequested = includeLyrics

            if (existing != null) {
                var lyricsFile = existing.lyricsFile
                if (includeLyrics && lyricsFile.isNullOrBlank() && lyricsText.isNotBlank()) {
                    lyricsFile = runCatching {
                        storeLyrics(context, trackId, lyricsText)
                    }.getOrNull()
                    if (lyricsFile != null) {
                        repository.updateTrackLyrics(trackId, lyricsFile)
                    }
                }
                return resultJson(
                    ok = true,
                    track = existing.copy(lyricsFile = lyricsFile),
                    alreadyExists = true,
                    lyricsRequested = lyricsRequested,
                    lyricsIncluded = !lyricsFile.isNullOrBlank(),
                    size = audioFile.length()
                )
            }

            if (!audioFile.exists() || audioFile.length() <= 0L) {
                return errorJson("下载的音频文件为空。")
            }

            val target = File(AudioFileStore.musicDir(context), "$trackId.${extensionOf(json)}")
            if (target.exists()) target.delete()
            moveIntoPlace(audioFile, target)
            if (!target.exists() || target.length() <= 0L) {
                target.delete()
                return errorJson("无法写入播放器存储空间。")
            }

            val lyricsFile = if (includeLyrics && lyricsText.isNotBlank()) {
                runCatching { storeLyrics(context, trackId, lyricsText) }.getOrNull()
            } else {
                null
            }
            val artworkFile = coverFile?.let { file ->
                runCatching { saveCover(context, trackId, file) }.getOrNull()
            } ?: repository.defaultArtworkFileName(trackId)

            val sourceName = json.optString("sourceName").ifBlank { source }
            val qualityLabel = json.optString("qualityLabel")
            val durationMs = readDurationMs(target)
                .takeIf { it > 0L }
                ?: (json.optLong("duration", 0L) * 1000L)
            val track = Track(
                id = trackId,
                uri = Uri.fromFile(target).toString(),
                title = json.optString("title").ifBlank { "未知歌曲" }.take(256),
                artist = json.optString("artist").ifBlank { "未知歌手" }.take(256),
                album = json.optString("album").ifBlank { "在线音乐" }.take(256),
                durationMs = durationMs.coerceAtLeast(0L),
                artworkFile = artworkFile,
                mimeType = json.optString("mime").ifBlank { null },
                addedAt = System.currentTimeMillis(),
                lyricsFile = lyricsFile,
                sourceUri = null,
                sourceName = buildString {
                    append("在线 · ").append(sourceName)
                    if (qualityLabel.isNotBlank()) {
                        append(" · ").append(qualityLabel.take(48))
                    }
                }
            )
            val added = repository.addTracks(listOf(track))
            if (added == 0) {
                return resultJson(
                    ok = true,
                    track = track,
                    alreadyExists = true,
                    lyricsRequested = lyricsRequested,
                    lyricsIncluded = !lyricsFile.isNullOrBlank(),
                    size = target.length()
                )
            }
            Log.i(TAG, "Imported online track ${track.title} (${target.length()} bytes)")
            return resultJson(
                ok = true,
                track = track,
                alreadyExists = false,
                lyricsRequested = lyricsRequested,
                lyricsIncluded = !lyricsFile.isNullOrBlank(),
                size = target.length()
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Could not add the downloaded track to the library", error)
            errorJson(
                when (error) {
                    is OutOfMemoryError -> "封面或文件过大，无法加入播放器。"
                    else -> "加入播放器失败，请稍后再试。"
                }
            )
        }
    }

    private fun storeLyrics(context: Context, trackId: String, text: String): String? {
        if (text.length > MAX_LYRICS_CHARS) return null
        return LyricsImporter.storeText(context, trackId, text)
    }

    private fun extensionOf(json: JSONObject): String {
        val ext = json.optString("ext").trim().lowercase(Locale.ROOT)
        return if (ext in audioExtensions) ext else "mp3"
    }

    private fun moveIntoPlace(source: File, target: File) {
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (error: Exception) {
            Log.w(TAG, "Could not read duration of the online audio", error)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun saveCover(context: Context, trackId: String, file: File): String? {
        if (!file.exists() || file.length() <= 0L || file.length() > MAX_COVER_BYTES) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 900) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val directory = File(context.filesDir, "artwork").apply { if (!exists()) mkdirs() }
        val outputFile = File(directory, "$trackId.jpg")
        return try {
            FileOutputStream(outputFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
            outputFile.name
        } finally {
            bitmap.recycle()
        }
    }

    private fun resultJson(
        ok: Boolean,
        track: Track,
        alreadyExists: Boolean,
        lyricsRequested: Boolean,
        lyricsIncluded: Boolean,
        size: Long
    ): String {
        return JSONObject()
            .put("ok", ok)
            .put("trackId", track.id)
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("alreadyExists", alreadyExists)
            .put("lyricsRequested", lyricsRequested)
            .put("lyricsIncluded", lyricsIncluded)
            .put("size", size)
            .toString()
    }

    private fun errorJson(message: String): String {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
            .toString()
    }
}
