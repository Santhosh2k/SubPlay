package com.example.offlinesubtitleplayer.domain.repository

import com.example.offlinesubtitleplayer.domain.model.VideoItem
import kotlinx.coroutines.flow.Flow

interface VideoRepository {
    fun getLocalVideos(): Flow<List<VideoItem>>
}
