package com.example.offlinesubtitleplayer.domain.model

import android.net.Uri

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val path: String
)
