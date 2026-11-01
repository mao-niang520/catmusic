package com.catmusic.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

data class MixedImportOutcome(
    val tracks: List<Track>,
    val skipped: Int,
    val failed: Int,
    val audioCount: Int,
    val lyricsMatched: Int,
    val coversMatched: Int
)

/**
 * One-shot import of audio together with its sidecar lyrics and cover art.
 *
 * Companions are paired by a normalized file-name stem, then by the embedded
 * track title. Files named cover / folder / front / 封面 are treated as an
 * album cover and may be shared by several tracks in the same folder. Nothing
 * is downloaded and every audio file is still copied into app-private storage
 * by [MusicImporter].
 */
object MixedImporter {

    private const val TAG = "MixedImporter"

    private val lyricExtensions = setOf("lrc", "txt")
    private val imageExtensions = setOf(
        "jpg", "jpeg", "jfif", "png", "webp", "bmp", "heic", "heif", "avif", "gif"
    )
    private val genericCoverKeys = setOf(
        "cover", "folder", "front", "album", "albumart", "albumartwork",
        "artwork", "folderart", "foldercover", "frontcover",
        "封面", "专辑封面", "专辑图", "封面图", "专辑封面图"
    )
    private val bracketRegex = Regex("[（(\\[【{].*?[）)\\]】}]")
    private val separatorRegex = Regex("[\\s_\\-—–·•'’`]+")
    private val nonWordRegex = Regex("[^\\p{L}\\p{N}]")
    private val leadingTrackNumberRegex = Regex("^\\s*\\d{1,3}\\s*(?:[.\\-_、)]\\s*|\\s+)")

    private enum class CompanionKind { LYRICS, IMAGE }

    private data class CompanionFile(
        val uri: Uri,
        val kind: CompanionKind,
        val key: String,
        val folder: String?,
        val genericCover: Boolean
    )

    private class AudioSlot(
        var track: Track,
        val keys: Set<String>,
        val folder: String?
    ) {
        var coverApplied: Boolean = false
    }

    fun looksLikeCompanion(name: String?, mime: String?): Boolean {
        if (name.isNullOrBlank() || name.startsWith(".")) return false
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in lyricExtensions || extension in imageExtensions) return true
        val normalizedMime = mime?.lowercase(Locale.ROOT) ?: return false
        if (normalizedMime.startsWith("image/")) return true
        return normalizedMime == "text/plain" &&
            (extension == "lrc" || extension == "txt")
    }

    suspend fun importMixed(
        context: Context,
        rawUris: List<Uri>,
        skipTrackIds: Set<String> = emptySet(),
        onProgress: (processed: Int, total: Int, parsed: Int) -> Unit = { _, _, _ -> }
    ): MixedImportOutcome = withContext(Dispatchers.IO) {
        val uniqueUris = rawUris.distinct()
        val audioUris = ArrayList<Uri>()
        val companions = ArrayList<CompanionFile>()

        uniqueUris.forEach { uri ->
            currentCoroutineContext().ensureActive()
            val name = queryDisplayName(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: return@forEach
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            when {
                MusicImporter.looksLikeAudio(name, mime) -> audioUris.add(uri)
                looksLikeCompanion(name, mime) -> {
                    val kind = when {
                        name.substringAfterLast('.', "")
                            .lowercase(Locale.ROOT) in lyricExtensions -> CompanionKind.LYRICS

                        mime?.startsWith("image/", ignoreCase = true) == true ->
                            CompanionKind.IMAGE

                        name.substringAfterLast('.', "")
                            .lowercase(Locale.ROOT) in imageExtensions -> CompanionKind.IMAGE

                        else -> CompanionKind.LYRICS
                    }
                    companions.add(
                        CompanionFile(
                            uri = uri,
                            kind = kind,
                            key = normalizedKey(stemOf(name)),
                            folder = folderKeyOf(uri),
                            genericCover = kind == CompanionKind.IMAGE &&
                                isGenericCoverName(name)
                        )
                    )
                }
            }
        }

        if (audioUris.isEmpty()) {
            return@withContext MixedImportOutcome(
                tracks = emptyList(),
                skipped = 0,
                failed = 0,
                audioCount = 0,
                lyricsMatched = 0,
                coversMatched = 0
            )
        }

        val totalWork = audioUris.size + companions.size
        val imported = MusicImporter.importUris(context, audioUris) { processed, _, parsed ->
            onProgress(processed, totalWork, parsed)
        }
        var processedCompanions = audioUris.size

        val slots = imported.tracks
            .filter { it.id !in skipTrackIds }
            .map { track ->
                val sourceName = track.sourceName?.takeIf { it.isNotBlank() } ?: track.title
                val sourceStem = stemOf(sourceName)
                val keys = listOf(
                    normalizedKey(sourceStem),
                    normalizedKey(stripLeadingTrackNumber(sourceStem)),
                    normalizedKey(track.title),
                    normalizedKey(stripLeadingTrackNumber(track.title))
                ).filter { it.isNotEmpty() }.toSet()
                val folder = track.sourceUri
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    ?.let(::folderKeyOf)
                AudioSlot(track = track, keys = keys, folder = folder)
            }

        var lyricsMatched = 0
        var coversMatched = 0
        val lyricsPool = companions
            .filter { it.kind == CompanionKind.LYRICS }
            .toMutableList()

        slots.forEach { slot ->
            currentCoroutineContext().ensureActive()
            val singleFallback = slots.size == 1 && lyricsPool.size == 1
            val match = pickCompanion(slot, lyricsPool, singleFallback) ?: return@forEach
            lyricsPool.remove(match)
            val fileName = runCatching {
                LyricsImporter.import(context, slot.track.id, match.uri)
            }.onFailure {
                Log.w(TAG, "Could not import lyrics ${match.uri}", it)
            }.getOrNull()
            if (fileName != null) {
                slot.track = slot.track.copy(lyricsFile = fileName)
                lyricsMatched++
            }
        }
        processedCompanions += companions.count { it.kind == CompanionKind.LYRICS }
        onProgress(processedCompanions, totalWork, imported.tracks.size)

        val coverPool = companions.filter { it.kind == CompanionKind.IMAGE }
        val specificCovers = coverPool.filterNot { it.genericCover }.toMutableList()
        val specificCoverCount = specificCovers.size
        slots.forEach { slot ->
            currentCoroutineContext().ensureActive()
            if (slot.coverApplied) return@forEach
            val match = pickCompanion(slot, specificCovers, slots.size == 1 && specificCovers.size == 1)
                ?: return@forEach
            specificCovers.remove(match)
            if (applyCover(context, slot, match)) coversMatched++
        }
        processedCompanions += specificCoverCount
        onProgress(processedCompanions, totalWork, imported.tracks.size)

        val genericCovers = coverPool.filter { it.genericCover }
        val folderlessFallback = genericCovers.firstOrNull { it.folder == null }
        slots.forEach { slot ->
            currentCoroutineContext().ensureActive()
            if (slot.coverApplied) return@forEach
            val sameFolder = genericCovers.firstOrNull {
                it.folder != null && slot.folder != null &&
                    it.folder.equals(slot.folder, ignoreCase = true)
            }
            val fallback = sameFolder ?: if (slot.folder == null) folderlessFallback else null
            if (fallback != null && applyCover(context, slot, fallback)) coversMatched++
        }

        if (coverPool.size == 1 && slots.isNotEmpty() && coversMatched == 0) {
            val onlyCover = coverPool.first()
            slots.forEach { slot ->
                currentCoroutineContext().ensureActive()
                if (!slot.coverApplied && applyCover(context, slot, onlyCover)) coversMatched++
            }
        }
        processedCompanions += genericCovers.size
        onProgress(processedCompanions, totalWork, imported.tracks.size)

        val updatedById = slots.associate { it.track.id to it.track }
        val tracks = imported.tracks.map { updatedById[it.id] ?: it }
        MixedImportOutcome(
            tracks = tracks,
            skipped = imported.skipped,
            failed = imported.failed,
            audioCount = audioUris.size,
            lyricsMatched = lyricsMatched,
            coversMatched = coversMatched
        )
    }

    private suspend fun applyCover(
        context: Context,
        slot: AudioSlot,
        cover: CompanionFile
    ): Boolean {
        val fileName = runCatching {
            ArtworkTransformer.import(context, slot.track.id, cover.uri)
        }.onFailure {
            Log.w(TAG, "Could not import cover ${cover.uri}", it)
        }.getOrNull() ?: return false
        slot.track = slot.track.copy(artworkFile = fileName)
        slot.coverApplied = true
        return true
    }

    private fun pickCompanion(
        slot: AudioSlot,
        pool: List<CompanionFile>,
        singleFallback: Boolean
    ): CompanionFile? {
        if (pool.isEmpty()) return null
        val sameFolder = pool.firstOrNull {
            it.folder != null && slot.folder != null &&
                it.folder.equals(slot.folder, ignoreCase = true) &&
                it.key in slot.keys
        }
        if (sameFolder != null) return sameFolder
        val sameName = pool.firstOrNull { it.key in slot.keys }
        if (sameName != null) return sameName
        return if (singleFallback) pool.first() else null
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

    private fun stemOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun stripLeadingTrackNumber(value: String): String =
        value.replaceFirst(leadingTrackNumberRegex, "")

    private fun normalizedKey(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(bracketRegex, "")
            .replace(separatorRegex, "")
            .replace(nonWordRegex, "")

    private fun isGenericCoverName(name: String): Boolean {
        val key = normalizedKey(stemOf(name))
        return key in genericCoverKeys ||
            key.startsWith("cover") ||
            key.startsWith("frontcover") ||
            key.startsWith("albumart")
    }

    /**
     * For ExternalStorageProvider URIs the document id contains the relative
     * path, so we can group sidecar files by their real folder. Providers
     * without a path (Downloads, MediaStore) return null and fall back to
     * file-name matching only.
     */
    private fun folderKeyOf(uri: Uri): String? {
        val decoded = Uri.decode(uri.toString())
        val document = decoded.substringAfter("/document/", "")
        if (document.isEmpty()) return null
        // The SAF document id is one URI path segment; after decoding it may
        // itself contain slashes (primary:Music/Album/song.mp3), so split the
        // folder from the whole remainder instead of the first raw slash.
        val documentId = document.substringBefore('?').trimEnd('/')
        if (!documentId.contains(':')) return null
        val relative = documentId.substringAfter(':')
        return relative.substringBeforeLast('/', "").ifBlank { null }
    }
}
