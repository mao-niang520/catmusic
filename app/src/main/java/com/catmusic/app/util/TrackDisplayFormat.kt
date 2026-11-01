package com.catmusic.app.util

import android.net.Uri
import com.catmusic.app.data.Track
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val knownAudioExtensions = setOf(
    "mp3", "m4a", "m4b", "m4r", "aac", "flac", "wav", "wave", "ogg", "oga",
    "opus", "amr", "awb", "mid", "midi", "mka", "webm", "aiff", "aif", "aifc",
    "wma", "ape", "dsf", "dff", "alac", "wv", "au", "ac3", "eac3", "caf", "3gp"
)

fun audioFormatLabel(track: Track?): String {
    if (track == null) return "AUDIO"
    val fromUri = Uri.parse(track.uri).lastPathSegment
        ?.substringAfterLast('.')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it in knownAudioExtensions }
    val fromMime = track.mimeType
        ?.substringAfter('/')
        ?.substringBefore(';')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() }
    val raw = fromUri ?: fromMime ?: "audio"
    return when (raw) {
        "mpeg", "mp3" -> "MP3"
        "mp4", "m4a", "x-m4a" -> "M4A"
        "x-flac", "flac" -> "FLAC"
        "x-wav", "wav", "wave" -> "WAV"
        "x-ogg", "ogg", "oga" -> "OGG"
        "opus" -> "OPUS"
        "aac", "x-aac" -> "AAC"
        "x-ms-wma", "wma" -> "WMA"
        "x-ape", "ape" -> "APE"
        "midi", "mid" -> "MIDI"
        else -> raw.uppercase(Locale.ROOT).take(8)
    }
}

fun formatFileSize(bytes: Long?): String {
    if (bytes == null || bytes < 0L) return "未知"
    if (bytes < 1_024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

fun formatAddedAt(epochMillis: Long): String {
    if (epochMillis <= 0L) return "未知"
    val dateTime = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    return dateTime.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 HH:mm", Locale.CHINA))
}

fun formatPlaylistUpdated(epochMillis: Long): String {
    if (epochMillis <= 0L) return "刚刚创建"
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "今天更新"
        date == today.minusDays(1) -> "昨天更新"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("M 月 d 日更新", Locale.CHINA))
        else -> date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA))
    }
}
