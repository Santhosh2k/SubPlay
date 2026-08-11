package com.example.offlinesubtitleplayer.domain.model

data class SubtitleCue(
    val id: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
) {
    fun isActiveAt(timeMs: Long): Boolean {
        return timeMs in startTimeMs..endTimeMs
    }
}
