package com.catmusic.app.data

/**
 * Frame-rate budget for the WebGL lyric stage. The phone panel still decides
 * the absolute ceiling; "unlimited" simply renders on every vsync.
 */
enum class LyricFrameRate(
    val key: String,
    val fps: Int,
    val title: String,
    val description: String
) {
    FPS_30("30", 30, "30", "最省电，动作仍然连贯"),
    FPS_45("45", 45, "45", "省电与顺滑的折中"),
    FPS_60("60", 60, "60", "标准流畅，适合大多数场景"),
    FPS_90("90", 90, "90", "高刷屏更跟手，发热略高"),
    FPS_120("120", 120, "120", "适合 120Hz 屏幕，耗电更高"),
    UNLIMITED("unlimited", 0, "无限制", "跟随屏幕刷新率，性能与耗电最高");

    companion object {
        fun fromKey(key: String?): LyricFrameRate =
            entries.firstOrNull { it.key == key } ?: FPS_60
    }
}
