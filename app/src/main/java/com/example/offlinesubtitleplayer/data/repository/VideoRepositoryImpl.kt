package com.example.offlinesubtitleplayer.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.offlinesubtitleplayer.domain.model.VideoItem
import com.example.offlinesubtitleplayer.domain.repository.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class VideoRepositoryImpl(private val context: Context) : VideoRepository {

    override fun getLocalVideos(): Flow<List<VideoItem>> = flow {
        val videoList = mutableListOf<VideoItem>()
        
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA
        )

        // Sort order: recently added first
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dataColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

            while (c.moveToNext()) {
                val id = c.getLong(idColumn)
                val displayName = c.getString(nameColumn) ?: "Unknown Video"
                val duration = c.getLong(durationColumn)
                val size = c.getLong(sizeColumn)
                val path = c.getString(dataColumn) ?: ""
                
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                videoList.add(
                    VideoItem(
                        id = id,
                        uri = uri,
                        displayName = displayName,
                        durationMs = duration,
                        sizeBytes = size,
                        path = path
                    )
                )
            }
        }
        
        emit(videoList)
    }.flowOn(Dispatchers.IO)
}
