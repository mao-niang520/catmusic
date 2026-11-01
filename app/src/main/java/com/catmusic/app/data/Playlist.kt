package com.catmusic.app.data

data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val trackCount: Int get() = trackIds.size
}
