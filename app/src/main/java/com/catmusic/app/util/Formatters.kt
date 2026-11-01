package com.catmusic.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

fun formatClock(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatStatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0 分钟"
    if (milliseconds < 60_000L) return "不到 1 分钟"
    val totalMinutes = milliseconds / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours 小时 $minutes 分钟"
        hours > 0L -> "$hours 小时"
        else -> "$minutes 分钟"
    }
}

fun formatCompactDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0 分钟"
    if (milliseconds < 60_000L) return "<1 分钟"
    val totalMinutes = milliseconds / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours 时 $minutes 分"
        hours > 0L -> "$hours 小时"
        else -> "$minutes 分钟"
    }
}

fun formatSongDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "--:--"
    val totalSeconds = (milliseconds / 1000.0).roundToLong()
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

fun formatTotalAsHoursMinutes(milliseconds: Long): Pair<Long, Long> {
    val totalMinutes = milliseconds / 60_000L
    return (totalMinutes / 60L) to (totalMinutes % 60L)
}

fun formatListenCount(count: Int): String {
    return if (count >= 10_000) {
        String.format(Locale.US, "%.1f 万次", count / 10_000f)
    } else {
        "${count} 次"
    }
}

fun weekdayShort(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}

fun friendlyDayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA)
            date.format(formatter)
        }
    }
}

fun greetingForNow(): String {
    val hour = LocalDateTime.now().hour
    return when (hour) {
        in 5..10 -> "早安，听点轻快的吧"
        in 11..13 -> "午安，给耳朵放个假"
        in 14..17 -> "下午好，来首喜欢的"
        in 18..22 -> "晚上好，让旋律陪你"
        else -> "夜深了，小点声听"
    }
}

fun dayKey(date: LocalDate): String = date.toString()
