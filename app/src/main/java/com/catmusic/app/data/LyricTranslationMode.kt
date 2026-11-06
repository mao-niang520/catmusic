package com.catmusic.app.data

/**
 * How CatMusic treats lyric translations.
 *
 * OFFLINE keeps the local file untouched except for hiding translations.
 * EMBEDDED shows translations supplied by the LRC itself.
 * ONLINE additionally fills missing translations for foreign-language lines
 * with an opt-in public translation request. Audio, covers and library data are
 * never sent.
 */
enum class LyricTranslationMode(
    val key: String,
    val title: String,
    val description: String
) {
    OFF(
        key = "off",
        title = "关闭",
        description = "只显示歌词原文"
    ),
    EMBEDDED(
        key = "embedded",
        title = "仅自带",
        description = "显示歌词文件自带的翻译，不联网"
    ),
    ONLINE(
        key = "online",
        title = "自动补全",
        description = "自带翻译缺失时，联网翻译外语歌词"
    );

    companion object {
        fun fromKey(key: String?): LyricTranslationMode =
            entries.firstOrNull { it.key == key } ?: EMBEDDED
    }
}
