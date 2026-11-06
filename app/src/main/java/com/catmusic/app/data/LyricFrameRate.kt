package com.catmusic.app.data

/**
 * Frame-rate budget for the native ambience and the WebGL lyric stage.
 *
 * The phone panel still decides the absolute ceiling. Adaptive mode reduces
 * ambient rendering while the screen is still and raises it briefly when the
 * user interacts or the lyrics / track change.
 */
enum class LyricFrameRate(
    val key: String,
    val fps: Int,
    val title: String,
    val description: String
) {
    ADAPTIVE("adaptive", 0, "自适应", "静止时约 24fps，播放 30fps，交互或换行时升到 60fps"),
    FPS_30("30", 30, "30", "固定 30fps，省电且动作连贯"),
    FPS_45("45", 45, "45", "固定 45fps，省电与顺滑的折中"),
    FPS_60("60", 60, "60", "固定 60fps，适合大多数场景"),
    FPS_90("90", 90, "90", "固定 90fps，高刷屏更跟手"),
    FPS_120("120", 120, "120", "固定 120fps，适合 120Hz 屏幕"),
    UNLIMITED("unlimited", 0, "无限制", "跟随屏幕刷新率，性能与耗电最高");

    companion object {
        fun fromKey(key: String?): LyricFrameRate =
            entries.firstOrNull { it.key == key } ?: ADAPTIVE
    }
}
