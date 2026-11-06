package com.catmusic.app.util

import java.util.LinkedHashSet

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val hasTiming: Boolean,
    val title: String? = null,
    val artist: String? = null
) {
    fun activeIndex(positionMs: Long): Int {
        if (!hasTiming || lines.isEmpty() || positionMs < lines.first().timeMs) return -1
        var low = 0
        var high = lines.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

object LrcParser {

    /** Lines whose timestamps are this close usually form a lyric/translation pair. */
    private const val TRANSLATION_GROUP_MS = 45L

    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val offsetTag = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
    private val metadataTag = Regex("^\\[[a-zA-Z]+:[^\\]]*]$")
    private val trailingTranslation =
        Regex("""\s*[（(]\s*([^()（）]{1,160}?)\s*[)）]\s*$""")

    private val plainAnnotations = setOf(
        "live", "remix", "version", "edit", "instrumental", "demo", "acoustic",
        "cover", "intro", "outro", "chorus", "verse", "bridge", "hook",
        "feat.", "feat", "ft.", "ft", "explicit", "clean", "ost", "tv size",
        "tv-size", "short ver.", "short version", "long version", "original mix"
    )

    fun parse(raw: String): ParsedLyrics {
        val normalized = raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val offsetMs = offsetTag.find(normalized)
            ?.groupValues?.getOrNull(1)
            ?.toLongOrNull()
            ?.coerceIn(-3_600_000L, 3_600_000L)
            ?: 0L

        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        var title: String? = null
        var artist: String? = null

        normalized.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("[ti:", ignoreCase = true) -> {
                    title = line.removePrefixAndBrackets()
                }

                line.startsWith("[ar:", ignoreCase = true) -> {
                    artist = line.removePrefixAndBrackets()
                }
            }

            val matches = timeTag.findAll(line).toList()
            if (matches.isNotEmpty()) {
                val rawText = line.substring(matches.last().range.last + 1).trim()
                if (rawText.isNotEmpty()) {
                    val (text, inlineTranslation) = splitTrailingTranslation(rawText)
                    matches.forEach { match ->
                        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                        val fraction = match.groupValues.getOrNull(3).orEmpty()
                        val time = (minutes * 60L + seconds) * 1000L +
                            fractionToMillis(fraction) + offsetMs
                        timed.add(
                            LyricLine(
                                time.coerceAtLeast(0L),
                                text,
                                inlineTranslation
                            )
                        )
                    }
                }
            } else if (!metadataTag.matches(line)) {
                val (text, inlineTranslation) = splitTrailingTranslation(line)
                if (text.isNotEmpty()) {
                    plain.add(LyricLine(0L, text, inlineTranslation))
                }
            }
        }

        if (timed.isNotEmpty()) {
            val sorted = timed.sortedBy { it.timeMs }
            val grouped = mutableListOf<LyricLine>()
            var index = 0
            while (index < sorted.size) {
                val first = sorted[index]
                val translations = LinkedHashSet<String>()
                first.translation
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(translations::add)
                var next = index + 1
                while (
                    next < sorted.size &&
                    sorted[next].timeMs - first.timeMs <= TRANSLATION_GROUP_MS
                ) {
                    val candidate = sorted[next]
                    candidate.translation
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(translations::add)
                    candidate.text
                        .trim()
                        .takeIf { it.isNotEmpty() && it != first.text }
                        ?.let(translations::add)
                    next++
                }
                val groupedLine = LyricLine(
                    timeMs = first.timeMs,
                    text = first.text,
                    translation = translations
                        .filterNot { it == first.text }
                        .joinToString("\n")
                        .ifBlank { null }
                )
                if (grouped.lastOrNull() != groupedLine) grouped.add(groupedLine)
                index = next
            }
            return ParsedLyrics(
                lines = grouped,
                hasTiming = true,
                title = title,
                artist = artist
            )
        }

        return ParsedLyrics(
            lines = plain,
            hasTiming = false,
            title = title,
            artist = artist
        )
    }

    /**
     * Online lyric catalogs often put the translation after the original line
     * in parentheses. Keeping it there makes the lyric look like a code comment
     * and duplicates the translation. Split only when the two sides use
     * different writing systems, so stage directions such as "Hello (Live)" and
     * genuinely parenthetical Chinese text stay in the original line.
     */
    private fun splitTrailingTranslation(text: String): Pair<String, String?> {
        val match = trailingTranslation.find(text) ?: return text to null
        val main = text.substring(0, match.range.first).trim()
        val inner = match.groupValues[1].trim()
        if (main.isEmpty() || inner.isEmpty()) return text to null
        if (inner.lowercase().trim('.', ' ', '：', ':') in plainAnnotations) {
            return text to null
        }
        val mainScript = scriptOf(main)
        val innerScript = scriptOf(inner)
        if (mainScript == "other" || innerScript == "other" || mainScript == innerScript) {
            return text to null
        }
        return main to inner.removePrefix("翻译：").removePrefix("译：").trim()
    }

    private fun scriptOf(value: String): String {
        var han = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        var cyrillic = 0
        var arabic = 0
        var thai = 0
        var greek = 0
        var devanagari = 0
        for (char in value) {
            when (char.code) {
                in 0x3040..0x30FF -> kana++
                in 0xAC00..0xD7AF -> hangul++
                in 0x3400..0x9FFF -> han++
                in 0x0400..0x052F -> cyrillic++
                in 0x0600..0x06FF, in 0x0750..0x077F, in 0x08A0..0x08FF -> arabic++
                in 0x0E00..0x0E7F -> thai++
                in 0x0370..0x03FF, in 0x1F00..0x1FFF -> greek++
                in 0x0900..0x097F -> devanagari++
                in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F -> latin++
            }
        }
        return when {
            kana > 0 -> "kana"
            hangul > 0 -> "hangul"
            han > 0 -> "han"
            cyrillic > 0 -> "cyrillic"
            arabic > 0 -> "arabic"
            thai > 0 -> "thai"
            greek > 0 -> "greek"
            devanagari > 0 -> "devanagari"
            latin > 0 -> "latin"
            else -> "other"
        }
    }

    private fun fractionToMillis(fraction: String): Long {
        if (fraction.isEmpty()) return 0L
        val number = fraction.take(3).toLongOrNull() ?: 0L
        return when (fraction.length) {
            1 -> number * 100L
            2 -> number * 10L
            else -> number
        }
    }

    private fun String.removePrefixAndBrackets(): String {
        return substringAfter(':', "").trim().removeSuffix("]").trim()
    }
}
