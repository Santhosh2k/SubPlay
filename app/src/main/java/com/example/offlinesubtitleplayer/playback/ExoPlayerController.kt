package com.example.offlinesubtitleplayer.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class ExoPlayerController(context: Context) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var positionTrackerJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _playbackState.value = state
            _duration.value = exoPlayer.duration.coerceAtLeast(0L)
            if (state == Player.STATE_READY) {
                _duration.value = exoPlayer.duration
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startTrackingPosition()
            } else {
                stopTrackingPosition()
            }
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    fun prepare(videoUri: Uri) {
        val mediaItem = MediaItem.fromUri(videoUri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    fun play() {
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _currentPosition.value = positionMs
    }

    fun release() {
        stopTrackingPosition()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    private fun startTrackingPosition() {
        positionTrackerJob?.cancel()
        positionTrackerJob = scope.launch {
            while (isActive) {
                _currentPosition.value = exoPlayer.currentPosition
                delay(100) // Faster poll so subtitle chunk switches track the playhead
            }
        }
    }

    private fun stopTrackingPosition() {
        positionTrackerJob?.cancel()
        positionTrackerJob = null
        _currentPosition.value = exoPlayer.currentPosition
    }
}
