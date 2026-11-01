package com.catmusic.app.data

/**
 * How much of the audio-reactive backdrop is rendered behind the landscape
 * information/lyrics panel. The top level deliberately ignores performance
 * budgets; it is aimed at flagship hardware.
 */
enum class AudioVisualLevel(
    val key: String,
    val rank: Int,
    val title: String,
    val description: String
) {
    LIGHT(
        key = "light",
        rank = 1,
        title = "轻",
        description = "低密度的地形柱与柔和微光，最安静的一档"
    ),
    BALANCED(
        key = "balanced",
        rank = 2,
        title = "标准",
        description = "完整的声音地形、中心暖光与少量漂浮微粒"
    ),
    IMMERSIVE(
        key = "immersive",
        rank = 3,
        title = "沉浸",
        description = "高密度地形、节拍涟漪与更多发光边缘"
    ),
    ULTRA(
        key = "ultra",
        rank = 4,
        title = "极致",
        description = "最细密的地形、金色辉光与全部粒子，优先视觉"
    );

    companion object {
        fun fromKey(key: String?): AudioVisualLevel =
            entries.firstOrNull { it.key == key } ?: IMMERSIVE
    }
}
