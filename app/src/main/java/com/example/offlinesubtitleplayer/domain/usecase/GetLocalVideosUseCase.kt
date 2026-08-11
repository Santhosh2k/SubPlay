package com.example.offlinesubtitleplayer.domain.usecase

import com.example.offlinesubtitleplayer.domain.model.VideoItem
import com.example.offlinesubtitleplayer.domain.repository.VideoRepository
import kotlinx.coroutines.flow.Flow

class GetLocalVideosUseCase(private val repository: VideoRepository) {
    operator fun invoke(): Flow<List<VideoItem>> {
        return repository.getLocalVideos()
    }
}