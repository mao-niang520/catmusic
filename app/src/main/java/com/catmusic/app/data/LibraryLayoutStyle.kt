package com.catmusic.app.data

/** How the home song collection is arranged. */
enum class LibraryLayoutStyle(
    val key: String,
    val title: String
) {
    LIST("list", "列表"),
    VINYL("vinyl", "唱片");

    companion object {
        fun fromKey(key: String?): LibraryLayoutStyle =
            entries.firstOrNull { it.key == key } ?: LIST
    }
}
