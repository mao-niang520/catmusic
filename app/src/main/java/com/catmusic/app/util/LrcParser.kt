package com.catmusic.app.util

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
        val plain = ArrayList<String>()
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
                val text = line.substring(matches.last().range.last + 1).trim()
                if (text.isNotEmpty()) {
                    matches.forEach { match ->
                        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                        val fraction = match.groupValues.getOrNull(3).orEmpty()
                        val time = (minutes * 60L + seconds) * 1000L +
                            fractionToMillis(fraction) + offsetMs
                        timed.add(LyricLine(time.coerceAtLeast(0L), text))
                    }
                }
            } else if (!metadataTag.matches(line)) {
                plain.add(line)
            }
        }

        if (timed.isNotEmpty()) {
            val sorted = timed.sortedBy { it.timeMs }
            val grouped = mutableListOf<LyricLine>()
            var index = 0
            while (index < sorted.size) {
                val first = sorted[index]
                val parts = mutableListOf(first.text)
                var next = index + 1
                while (
                    next < sorted.size &&
                    sorted[next].timeMs - first.timeMs <= TRANSLATION_GROUP_MS
                ) {
                    parts.add(sorted[next].text)
                    next++
                }
                val groupedLine = LyricLine(
                    timeMs = first.timeMs,
                    text = parts.first(),
                    translation = if (parts.size > 1) {
                        parts.drop(1).joinToString(" / ")
                    } else {
                        null
                    }
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
            lines = plain.map { LyricLine(0L, it) },
            hasTiming = false,
            title = title,
            artist = artist
        )
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
