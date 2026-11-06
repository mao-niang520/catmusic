package com.catmusic.app.data

/** Visual treatment for the desktop lyric window. */
enum class DesktopLyricsStyle(
    val key: String,
    val title: String
) {
    OUTLINE("outline", "描边"),
    CLEAR("clear", "透明"),
    CARD("card", "卡片"),
    NEON("neon", "霓虹");

    companion object {
        fun fromKey(key: String?): DesktopLyricsStyle =
            entries.firstOrNull { it.key == key } ?: OUTLINE
    }
}

data class DesktopLyricColor(
    val title: String,
    val argb: Int
)

/**
 * Persisted look of the floating lyric window. All colours are opaque; the
 * window itself applies its own alpha so the text stays readable over games.
 */
data class DesktopLyricsConfig(
    val style: DesktopLyricsStyle = DesktopLyricsStyle.OUTLINE,
    val colorArgb: Int = DEFAULT_COLOR,
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val locked: Boolean = false
) {
    fun normalized(): DesktopLyricsConfig = copy(
        fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    )

    fun nextStyle(): DesktopLyricsStyle {
        val values = DesktopLyricsStyle.entries
        return values[(values.indexOf(style) + 1) % values.size]
    }

    fun nextColor(): DesktopLyricColor {
        val index = palette.indexOfFirst { it.argb == colorArgb }
        return palette[(index + 1 + palette.size) % palette.size]
    }

    companion object {
        const val DEFAULT_COLOR = 0xFFFFF8ED.toInt()
        const val DEFAULT_FONT_SIZE_SP = 24f
        const val MIN_FONT_SIZE_SP = 14f
        const val MAX_FONT_SIZE_SP = 40f
        const val FONT_SIZE_STEP_SP = 2f

        val palette: List<DesktopLyricColor> = listOf(
            DesktopLyricColor("奶白", 0xFFFFF8ED.toInt()),
            DesktopLyricColor("柠檬", 0xFFEAFF58.toInt()),
            DesktopLyricColor("薄荷", 0xFF7EE7C3.toInt()),
            DesktopLyricColor("樱粉", 0xFFFF9EC4.toInt()),
            DesktopLyricColor("星紫", 0xFFB7A6FF.toInt()),
            DesktopLyricColor("天蓝", 0xFF8FD3FF.toInt()),
            DesktopLyricColor("蜜桃", 0xFFFFC879.toInt())
        )
    }
}
