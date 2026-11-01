package com.catmusic.app.data

/**
 * A local-only visual preset for the main tabs. CUSTOM refers to a user-picked
 * image copied into CatMusic's private files directory.
 */
enum class HomeBackgroundStyle(
    val key: String,
    val title: String,
    val description: String
) {
    AURORA("aurora", "极光", "冷暖交织的极光与细碎星尘"),
    LIQUID("liquid", "液态流光", "虹彩光团与声波圆环，玻璃感最强"),
    STARFIELD("starfield", "星海", "深蓝星野、稀疏星云与远山"),
    DUSK("dusk", "暮色", "暖橙与玫瑰色的黄昏"),
    MINT("mint", "薄荷", "清透的青绿薄雾"),
    MINIMAL("minimal", "墨黑", "近乎纯黑的沉浸底色"),
    CUSTOM("custom", "自定义图片", "使用本地图片，随时可以更换");

    companion object {
        fun fromKey(value: String?): HomeBackgroundStyle =
            entries.firstOrNull { it.key == value } ?: AURORA
    }
}
