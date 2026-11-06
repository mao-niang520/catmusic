package com.catmusic.app.data

import android.content.Context
import android.text.Html
import android.util.Log
import com.catmusic.app.util.LrcParser
import com.catmusic.app.util.ParsedLyrics
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Single entry point for lyric files used by the native stage, the WebGL stage
 * and the desktop overlay.
 *
 * Online completion is deliberately opt-in and only ever sends the text of
 * foreign-language lyric lines that have no translation in the file. Audio,
 * artwork, playlists and listening history never leave the device.
 */
object LyricDisplayLoader {

    private const val TAG = "CatMusicLyrics"
    private const val CACHE_DIR = "lyrics-translations"
    private const val CACHE_VERSION = 1
    private const val QUERY_LIMIT = 450
    private const val MAX_LINES = 140
    private const val MAX_CHARS = 12_000
    private const val FAIL_RETRY_MS = 6L * 60L * 60L * 1000L

    private val failedAt = ConcurrentHashMap<String, Long>()
    private val cacheLock = Any()

    /** Fast local read. Never touches the network. */
    suspend fun load(
        context: Context,
        track: Track?,
        mode: LyricTranslationMode
    ): ParsedLyrics? {
        val key = track?.lyricsFile?.takeIf { it.isNotBlank() } ?: return null
        return withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "lyrics/$key")
            if (!file.exists()) return@withContext null
            val parsed = runCatching { LrcParser.parse(file.readText()) }.getOrNull()
                ?: return@withContext null
            if (parsed.lines.isEmpty()) return@withContext null
            when (mode) {
                LyricTranslationMode.OFF ->
                    parsed.copy(lines = parsed.lines.map { it.copy(translation = null) })
                LyricTranslationMode.EMBEDDED,
                LyricTranslationMode.ONLINE -> parsed
            }
        }
    }

    /**
     * Fills missing translations for foreign lines. The original document is
     * returned untouched when there is nothing to do or the network is down.
     */
    suspend fun enrich(
        context: Context,
        track: Track?,
        parsed: ParsedLyrics
    ): ParsedLyrics {
        val trackId = track?.id?.takeIf { it.isNotBlank() } ?: return parsed
        if (!parsed.lines.any {
                it.translation.isNullOrBlank() && isForeignLine(it.text)
            }
        ) {
            return parsed
        }

        val candidates = ArrayList<Pair<Int, String>>()
        var chars = 0
        for ((index, line) in parsed.lines.withIndex()) {
            if (!line.translation.isNullOrBlank() || !isForeignLine(line.text)) continue
            val text = line.text.trim()
            if (text.isEmpty()) continue
            if (candidates.size >= MAX_LINES || chars + text.length > MAX_CHARS) break
            candidates.add(index to text)
            chars += text.length
        }
        if (candidates.isEmpty()) return parsed

        val cache = withContext(Dispatchers.IO) { readCache(context, trackId) }
        val additions = HashMap<Int, String>()
        val byIndex = HashMap<String, MutableList<Int>>()
        for ((index, text) in candidates) {
            val cached = cache[text]
            if (!cached.isNullOrBlank()) {
                additions[index] = cached
                continue
            }
            if (isRecentFailure(text)) continue
            byIndex.getOrPut(text) { ArrayList() }.add(index)
        }

        if (byIndex.isNotEmpty()) {
            val updates = HashMap<String, String>()
            for ((language, texts) in byIndex.keys.groupBy(::detectSourceLanguage)) {
                for ((chunk, query) in chunks(texts)) {
                    val translated = translateChunk(query, language)
                    if (translated != null && translated.size == chunk.size) {
                        val now = System.currentTimeMillis()
                        for (position in chunk.indices) {
                            val value = translated[position].trim()
                            if (value.isNotBlank() &&
                                !value.equals(chunk[position], ignoreCase = true)
                            ) {
                                updates[chunk[position]] = value
                                byIndex[chunk[position]]?.forEach { additions[it] = value }
                            } else {
                                // A proper noun can come back unchanged. Remember
                                // it so reopening the lyrics does not send the
                                // same line to the service again.
                                failedAt[chunk[position]] = now
                            }
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        chunk.forEach { failedAt[it] = now }
                    }
                    delay(90L)
                }
            }
            if (updates.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    writeCache(context, trackId, cache + updates)
                }
            }
        }

        if (additions.isEmpty()) return parsed
        return parsed.copy(
            lines = parsed.lines.mapIndexed { index, line ->
                val translated = additions[index]
                if (line.translation.isNullOrBlank() && !translated.isNullOrBlank()) {
                    line.copy(translation = translated)
                } else {
                    line
                }
            }
        )
    }

    private fun chunks(texts: List<String>): List<Pair<List<String>, String>> {
        val result = ArrayList<Pair<List<String>, String>>()
        var current = ArrayList<String>()
        var currentQuery = ""

        fun flush() {
            if (current.isEmpty()) return
            result.add(current.toList() to currentQuery)
            current = ArrayList()
            currentQuery = ""
        }

        for (text in texts) {
            val candidate = if (current.isEmpty()) text else "$currentQuery\n$text"
            val encodedLength = runCatching {
                URLEncoder.encode(candidate, "UTF-8").length
            }.getOrDefault(Int.MAX_VALUE)
            if (encodedLength > QUERY_LIMIT) {
                flush()
                val singleLength = runCatching {
                    URLEncoder.encode(text, "UTF-8").length
                }.getOrDefault(Int.MAX_VALUE)
                if (singleLength <= QUERY_LIMIT) {
                    current.add(text)
                    currentQuery = text
                }
            } else {
                current.add(text)
                currentQuery = candidate
            }
        }
        flush()
        return result
    }

    private suspend fun translateChunk(text: String, sourceLanguage: String): List<String>? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = URL(
                    "https://api.mymemory.translated.net/get?q=$encoded" +
                        "&langpair=${sourceLanguage}%7Czh-CN"
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "CatMusic/Android")
                }
                if (connection.responseCode !in 200..299) return@withContext null
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
                if (root.optInt("responseStatus", 0) != 200) return@withContext null
                val data = root.optJSONObject("responseData") ?: return@withContext null
                val raw = data.optString("translatedText").takeIf { it.isNotBlank() }
                    ?: return@withContext null
                val decoded = runCatching {
                    Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                }.getOrDefault(raw)
                val lines = decoded.replace("\r", "").trimEnd('\n').split('\n')
                if (lines.size != text.split('\n').size) null else lines
            } catch (error: Throwable) {
                Log.w(TAG, "Online lyric translation failed", error)
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun isRecentFailure(text: String): Boolean {
        val at = failedAt[text] ?: return false
        return System.currentTimeMillis() - at < FAIL_RETRY_MS
    }

    private fun isForeignLine(text: String): Boolean {
        var han = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        var otherLetter = 0
        for (char in text) {
            if (!char.isLetter()) continue
            when (Character.UnicodeScript.of(char.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.LATIN -> latin++
                else -> otherLetter++
            }
        }
        if (kana > 0 || hangul > 0) return true
        if (han == 0) return latin + otherLetter >= 2
        // A mostly Chinese line with an English word is still Chinese.
        return latin > 0 && latin > han * 2
    }

    private fun detectSourceLanguage(text: String): String {
        var kana = false
        var hangul = false
        var cyrillic = false
        var arabic = false
        var thai = false
        var greek = false
        var devanagari = false
        for (char in text) {
            if (!char.isLetter()) continue
            when (Character.UnicodeScript.of(char.code)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> kana = true
                Character.UnicodeScript.HANGUL -> hangul = true
                Character.UnicodeScript.CYRILLIC -> cyrillic = true
                Character.UnicodeScript.ARABIC -> arabic = true
                Character.UnicodeScript.THAI -> thai = true
                Character.UnicodeScript.GREEK -> greek = true
                Character.UnicodeScript.DEVANAGARI -> devanagari = true
                else -> Unit
            }
        }
        return when {
            kana -> "ja"
            hangul -> "ko"
            cyrillic -> "ru"
            arabic -> "ar"
            thai -> "th"
            greek -> "el"
            devanagari -> "hi"
            else -> "en"
        }
    }

    fun deleteCache(context: Context, trackId: String) {
        synchronized(cacheLock) {
            runCatching { cacheFile(context, trackId).delete() }
        }
    }

    fun clearCache(context: Context) {
        synchronized(cacheLock) {
            runCatching {
                File(context.filesDir, CACHE_DIR).listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun cacheFile(context: Context, trackId: String): File {
        val directory = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val safeName = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "$safeName.json")
    }

    private fun readCache(context: Context, trackId: String): Map<String, String> {
        val file = cacheFile(context, trackId)
        if (!file.exists()) return emptyMap()
        synchronized(cacheLock) {
            return runCatching {
                val root = JSONObject(file.readText())
                if (root.optInt("version", 0) != CACHE_VERSION) return emptyMap()
                val map = root.optJSONObject("translations") ?: return emptyMap()
                buildMap {
                    map.keys().forEach { key ->
                        val value = map.optString(key)
                        if (value.isNotBlank()) put(key, value)
                    }
                }
            }.getOrElse {
                Log.w(TAG, "Unable to read lyric translation cache", it)
                emptyMap()
            }
        }
    }

    private fun writeCache(
        context: Context,
        trackId: String,
        values: Map<String, String>
    ) {
        val file = cacheFile(context, trackId)
        synchronized(cacheLock) {
            runCatching {
                val map = JSONObject()
                values.forEach { (key, value) -> map.put(key, value) }
                val root = JSONObject()
                    .put("version", CACHE_VERSION)
                    .put("updatedAt", System.currentTimeMillis())
                    .put("translations", map)
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(root.toString())
                if (!temp.renameTo(file)) {
                    file.writeText(root.toString())
                    temp.delete()
                }
            }.onFailure {
                Log.w(TAG, "Unable to persist lyric translations", it)
            }
        }
    }
}
