package com.catmusic.app.data

import android.content.Context
import android.net.Uri
import com.catmusic.app.util.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/**
 * Reads local lyrics, auto-detects common Chinese encodings, validates that at
 * least one line can be parsed, and stores a UTF-8 copy in app-private storage.
 */
object LyricsImporter {

    private const val MAX_INPUT_BYTES = 2 * 1024 * 1024

    suspend fun import(context: Context, trackId: String, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    readLimited(it, MAX_INPUT_BYTES)
                }
            }.getOrNull() ?: return@withContext null
            if (bytes.isEmpty()) return@withContext null

            val decoded = decodeText(bytes) ?: return@withContext null
            storeDecoded(context, trackId, decoded)
        }

    /**
     * Stores lyrics supplied directly by an online music source, using the same
     * encoding cleanup and LRC validation as file imports.
     */
    fun storeText(context: Context, trackId: String, text: String): String? {
        if (text.isBlank()) return null
        return storeDecoded(context, trackId, text)
    }

    private fun storeDecoded(context: Context, trackId: String, decoded: String): String? {
        val normalized = decoded
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || it.code >= 32 }
            .trim()

        if (normalized.isBlank()) return null
        if (LrcParser.parse(normalized).lines.isEmpty()) return null

        val directory = File(context.filesDir, "lyrics").apply { mkdirs() }
        val fileName = "$trackId-${System.currentTimeMillis()}.lrc"
        File(directory, fileName).writeText(normalized, Charsets.UTF_8)
        return fileName
    }

    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            if (bytes.size >= 3 &&
                b0 == 0xEF &&
                b1 == 0xBB &&
                (bytes[2].toInt() and 0xFF) == 0xBF
            ) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
        }

        val guessedUtf16 = guessUtf16WithoutBom(bytes)
        if (guessedUtf16 != null) return guessedUtf16

        val strictUtf8 = runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (!strictUtf8.isNullOrBlank()) return strictUtf8

        return runCatching {
            if (Charset.isSupported("GB18030")) {
                val decoder = Charset.forName("GB18030").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } else {
                null
            }
        }.getOrNull()
    }

    private fun guessUtf16WithoutBom(bytes: ByteArray): String? {
        val limit = minOf(bytes.size, 128)
        if (limit < 8) return null
        var evenZeros = 0
        var oddZeros = 0
        for (index in 0 until limit) {
            if (bytes[index].toInt() == 0) {
                if (index % 2 == 0) evenZeros++ else oddZeros++
            }
        }
        val pairs = limit / 2
        return when {
            oddZeros > pairs * 0.35f && evenZeros < pairs * 0.08f ->
                runCatching { String(bytes, Charsets.UTF_16LE) }.getOrNull()

            evenZeros > pairs * 0.35f && oddZeros < pairs * 0.08f ->
                runCatching { String(bytes, Charsets.UTF_16BE) }.getOrNull()

            else -> null
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IllegalArgumentException("歌词文件过大")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
