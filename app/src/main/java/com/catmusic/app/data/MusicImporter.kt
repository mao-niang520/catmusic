package com.catmusic.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Imports audio with the Storage Access Framework into an app-private copy.
 * Once the copy is complete the original document is no longer needed, so
 * deleting or moving the original file does not affect playback in CatMusic.
 */
object MusicImporter {

    private const val TAG = "MusicImporter"
    private const val MAX_SCANNED_NODES = 30_000

    private val audioExtensions = setOf(
        "mp3", "m4a", "m4b", "m4r", "aac", "flac", "wav", "wave", "ogg", "oga",
        "opus", "amr", "awb", "mid", "midi", "mka", "webm", "aiff", "aif", "aifc",
        "wma", "ape", "dsf", "dff", "alac", "wv", "au", "ac3", "eac3", "caf", "3gp"
    )

    private val extraAudioMimeTypes = setOf(
        "application/ogg",
        "application/x-ogg",
        "audio/x-ogg",
        "audio/flac",
        "audio/x-flac",
        "audio/opus",
        "audio/x-ms-wma",
        "audio/x-ape"
    )

    suspend fun collectTreeUris(
        context: Context,
        treeUri: Uri,
        includeCompanions: Boolean = false
    ): List<Uri> =
        withContext(Dispatchers.IO) {
            val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
                ?: return@withContext emptyList()
            val queue = ArrayDeque<DocumentFile>()
            val found = LinkedHashSet<Uri>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_SCANNED_NODES) {
                currentCoroutineContext().ensureActive()
                val directory = queue.removeFirst()
                val children = runCatching { directory.listFiles() }.getOrElse {
                    Log.w(TAG, "Could not list ${directory.uri}", it)
                    emptyArray()
                }
                for (child in children) {
                    visited++
                    if (visited >= MAX_SCANNED_NODES) break
                    if (child.isDirectory) {
                        queue.add(child)
                    } else if (child.isFile &&
                        (looksLikeAudio(child.name, child.type) ||
                            (includeCompanions &&
                                MixedImporter.looksLikeCompanion(child.name, child.type)))
                    ) {
                        found.add(child.uri)
                    }
                }
            }
            found.toList()
        }

    suspend fun importUris(
        context: Context,
        rawUris: List<Uri>,
        onProgress: (processed: Int, total: Int, parsed: Int) -> Unit = { _, _, _ -> }
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val uniqueUris = rawUris.distinct()
        val tracks = ArrayList<Track>(uniqueUris.size)
        var skipped = 0
        var failed = 0

        uniqueUris.forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val displayName = queryDisplayName(context, uri) ?: fallbackName(uri)
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()

            if (!looksLikeAudio(displayName, mimeType)) {
                skipped++
            } else {
                val trackId = trackIdFor(uri)
                val managedFile = AudioFileStore.copyToManaged(
                    context = context,
                    trackId = trackId,
                    sourceUri = uri,
                    displayName = displayName,
                    mimeType = mimeType
                )

                if (managedFile == null) {
                    failed++
                } else {
                    val track = runCatching {
                        readTrack(
                            context = context,
                            trackId = trackId,
                            managedFile = managedFile,
                            displayName = displayName,
                            mimeType = mimeType,
                            sourceUri = uri,
                            index = index
                        )
                    }.onFailure {
                        Log.w(TAG, "Could not read metadata for $uri", it)
                    }.getOrNull()
                    if (track != null) {
                        tracks.add(track)
                    } else {
                        failed++
                    }
                }
            }
            onProgress(index + 1, uniqueUris.size, tracks.size)
        }

        ImportOutcome(tracks = tracks, skipped = skipped, failed = failed)
    }

    private fun readTrack(
        context: Context,
        trackId: String,
        managedFile: File,
        displayName: String,
        mimeType: String?,
        sourceUri: Uri,
        index: Int
    ): Track {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        var picture: ByteArray? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(managedFile.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            picture = retriever.embeddedPicture
        } catch (error: Exception) {
            Log.w(TAG, "Metadata extraction failed, using file name fallback", error)
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Cover art too large", error)
        } finally {
            runCatching { retriever.release() }
        }

        val artworkFile = picture?.let { bytes ->
            runCatching { saveArtwork(context, trackId, bytes) }
                .onFailure { Log.w(TAG, "Could not save artwork", it) }
                .getOrNull()
        }

        val fallbackTitle = displayName.substringBeforeLast('.').ifBlank { "未知音频" }
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() } ?: "未知艺术家"
        val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() } ?: "未知专辑"

        return Track(
            id = trackId,
            uri = Uri.fromFile(managedFile).toString(),
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            durationMs = durationMs.coerceAtLeast(0L),
            artworkFile = artworkFile,
            mimeType = mimeType,
            addedAt = System.currentTimeMillis() + index,
            sourceUri = sourceUri.toString(),
            sourceName = displayName
        )
    }

    private fun saveArtwork(context: Context, trackId: String, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 900) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val directory = File(context.filesDir, "artwork").apply { if (!exists()) mkdirs() }
        val file = File(directory, "$trackId.jpg")
        return try {
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
            file.name
        } finally {
            bitmap.recycle()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) cursor.getString(column) else null
                } else {
                    null
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun fallbackName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "未知音频"
    }

    fun looksLikeAudio(name: String?, mime: String?): Boolean {
        if (name?.startsWith(".") == true) return false
        val normalizedMime = mime?.lowercase(Locale.ROOT)
        if (normalizedMime?.startsWith("audio/") == true) return true
        if (normalizedMime != null && normalizedMime in extraAudioMimeTypes) return true
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return extension in audioExtensions
    }

    private fun trackIdFor(uri: Uri): String {
        return UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8)).toString()
    }
}
