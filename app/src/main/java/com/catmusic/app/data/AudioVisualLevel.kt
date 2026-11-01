package com.catmusic.app.data

/**
 * Fine-grained density for the full-screen sound field. The default gives the
 * cover-wrapped globe its full ten-thousand-point shell; the top level also
 * raises the tide lattice and pixel density for flagship hardware.
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
        description = "较疏的星潮与柔和回声，最安静的一档"
    ),
    BALANCED(
        key = "balanced",
        rank = 2,
        title = "标准",
        description = "清晰的封面星潮与流动声波"
    ),
    IMMERSIVE(
        key = "immersive",
        rank = 3,
        title = "沉浸",
        description = "约一万点封面星潮、节拍涟漪与更多辉光"
    ),
    ULTRA(
        key = "ultra",
        rank = 4,
        title = "极致",
        description = "拉满星潮、波层与辉光，优先视觉"
    );

    companion object {
        fun fromKey(key: String?): AudioVisualLevel =
            entries.firstOrNull { it.key == key } ?: IMMERSIVE
    }
}
