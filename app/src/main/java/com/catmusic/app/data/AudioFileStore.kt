package com.catmusic.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Owns the app's private audio copies. Imported songs no longer depend on the
 * original document after this copy completes; deleting an external file has
 * no effect on the library. Only this private copy is deleted from the app.
 */
object AudioFileStore {

    private const val MUSIC_DIR = "music"

    fun musicDir(context: Context): File {
        return File(context.filesDir, MUSIC_DIR).apply { if (!exists()) mkdirs() }
    }

    fun isManagedUri(context: Context, uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return runCatching {
            val root = musicDir(context).canonicalFile
            val file = File(path).canonicalFile
            file.path.startsWith(root.path + File.separator)
        }.getOrDefault(false)
    }

    fun managedPath(context: Context, uriString: String): String? {
        if (!isManagedUri(context, uriString)) return null
        return runCatching { File(Uri.parse(uriString).path ?: return null).absolutePath }
            .getOrNull()
    }

    /**
     * Copies an external audio document into app-private storage. The track ID
     * makes the operation idempotent, so re-imports and interrupted migrations
     * cannot create duplicate copies.
     */
    fun copyToManaged(
        context: Context,
        trackId: String,
        sourceUri: Uri,
        displayName: String?,
        mimeType: String?
    ): File? {
        val directory = musicDir(context)
        val safeId = trackId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { return null }

        if (sourceUri.scheme == "file") {
            val sourcePath = sourceUri.path
            if (sourcePath != null) {
                val candidate = File(sourcePath)
                if (candidate.exists() && isManagedUri(context, sourceUri.toString())) {
                    return candidate
                }
            }
        }

        directory.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("$safeId.") && !it.name.endsWith(".part")
        }?.let { existing ->
            if (existing.length() > 0L) return existing
        }

        val extension = resolveExtension(displayName, mimeType, sourceUri)
        val target = File(directory, "$safeId.$extension")
        if (target.exists() && target.length() > 0L) return target

        val temp = File.createTempFile("import-", ".part", directory)
        return try {
            val input = if (sourceUri.scheme == "file") {
                val path = sourceUri.path ?: return null
                runCatching { FileInputStream(File(path)) }.getOrNull()
            } else {
                context.contentResolver.openInputStream(sourceUri)
            } ?: run {
                temp.delete()
                return null
            }
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    source.copyTo(output, DEFAULT_BUFFER_SIZE * 4)
                    output.flush()
                }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (!target.exists() || target.length() <= 0L) {
                target.delete()
                null
            } else {
                target
            }
        } catch (_: Exception) {
            temp.delete()
            null
        } catch (_: OutOfMemoryError) {
            temp.delete()
            null
        }
    }

    fun deleteManagedAudio(context: Context, uriString: String) {
        val path = managedPath(context, uriString) ?: return
        runCatching { File(path).delete() }
    }

    fun deleteAllAudio(context: Context) {
        runCatching { musicDir(context).listFiles()?.forEach { it.delete() } }
    }

    fun fileSizeOfManaged(context: Context, uriString: String): Long? {
        val path = managedPath(context, uriString) ?: return null
        return runCatching { File(path).length().takeIf { it >= 0L } }.getOrNull()
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.takeIf { it.isNotBlank() }
        }
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun resolveExtension(
        displayName: String?,
        mimeType: String?,
        sourceUri: Uri
    ): String {
        val fromName = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 1..8 && it.all { char -> char.isLetterOrDigit() } }
        if (fromName != null) return fromName

        val fromMime = mimeType
            ?.substringBefore(';')
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 1..8 && it.all { char -> char.isLetterOrDigit() } }
        if (fromMime != null) return fromMime

        val fromUri = sourceUri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 1..8 && it.all { char -> char.isLetterOrDigit() } }
        return fromUri ?: "audio"
    }
}
