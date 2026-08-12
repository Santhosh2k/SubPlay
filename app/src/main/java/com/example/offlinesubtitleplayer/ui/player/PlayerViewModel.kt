package com.example.offlinesubtitleplayer.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinesubtitleplayer.data.extractor.AudioExtractor
import com.example.offlinesubtitleplayer.data.parser.SrtParser
import com.example.offlinesubtitleplayer.data.transcription.WhisperTranscriptionEngineImpl
import com.example.offlinesubtitleplayer.domain.model.SubtitleCue
import com.example.offlinesubtitleplayer.domain.transcription.TranscriptionEngine
import com.example.offlinesubtitleplayer.playback.ExoPlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class PlayerViewModel(
    private val context: Context,
    private val videoUriString: String,
    private val videoTitle: String
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
        // Short windows so Whisper finishes near playback time (30s was too slow).
        private const val CHUNK_DURATION_MS = 3000L
        private const val PREFETCH_AHEAD_CHUNKS = 2L
    }

    val playerController = ExoPlayerController(context)
    private val audioExtractor = AudioExtractor(context)
    private val transcriptionEngine: TranscriptionEngine =
        WhisperTranscriptionEngineImpl(context, "ggml-tiny.bin")

    private val videoUri = Uri.parse(videoUriString)

    private val _isSubtitleEnabled = MutableStateFlow(true)
    val isSubtitleEnabled: StateFlow<Boolean> = _isSubtitleEnabled.asStateFlow()

    private val _isExternalSrtActive = MutableStateFlow(false)
    val isExternalSrtActive: StateFlow<Boolean> = _isExternalSrtActive.asStateFlow()

    private val _activeSubtitleText = MutableStateFlow("")
    val activeSubtitleText: StateFlow<String> = _activeSubtitleText.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val externalCues = mutableListOf<SubtitleCue>()

    // ChunkIndex -> transcribed text (only successful inference results, including silence "").
    private val aiTranscribedCache = ConcurrentHashMap<Long, String>()
    private val ongoingTranscriptions = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var isEngineReady = false

    init {
        playerController.prepare(videoUri)

        viewModelScope.launch {
            _statusText.value = "Initializing Translation AI..."
            val success = transcriptionEngine.initialize()
            isEngineReady = success
            if (success) {
                _statusText.value = "AI Engine Ready"
                // Kick off the chunk under the playhead (and prefetch) now that Whisper is loaded.
                updateSubtitlesForPosition(playerController.currentPosition.value)
            } else {
                _statusText.value = "AI Initialization Failed — add ggml-tiny.en.bin to assets"
            }
        }

        autoDetectCompanionSrt()

        viewModelScope.launch {
            playerController.currentPosition.collect { positionMs ->
                updateSubtitlesForPosition(positionMs)
            }
        }
    }

    fun toggleSubtitles() {
        _isSubtitleEnabled.value = !_isSubtitleEnabled.value
        if (!_isSubtitleEnabled.value) {
            _activeSubtitleText.value = ""
        }
    }

    fun loadExternalSrt(inputStream: InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _statusText.value = "Parsing Subtitles..."
                val parsed = SrtParser.parse(inputStream)
                externalCues.clear()
                externalCues.addAll(parsed)

                _isExternalSrtActive.value = true
                _statusText.value = "Loaded ${parsed.size} SRT subtitles"
                Log.d(TAG, "Successfully loaded external SRT file with ${parsed.size} cues")
            } catch (e: Exception) {
                _statusText.value = "Error parsing subtitle file"
                Log.e(TAG, "Error parsing manual SRT", e)
            }
        }
    }

    fun clearExternalSubtitles() {
        externalCues.clear()
        _isExternalSrtActive.value = false
        _statusText.value = "AI Transcription Mode Active"
    }

    private fun autoDetectCompanionSrt() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = videoUri.path
                if (!path.isNullOrEmpty()) {
                    val videoFile = File(path)
                    if (videoFile.exists()) {
                        val baseName = videoFile.nameWithoutExtension
                        val companionSrt = File(videoFile.parent, "$baseName.srt")
                        if (companionSrt.exists() && companionSrt.canRead()) {
                            _statusText.value = "Auto-detected companion SRT"
                            val parsed = SrtParser.parse(companionSrt.inputStream())
                            externalCues.clear()
                            externalCues.addAll(parsed)
                            _isExternalSrtActive.value = true
                            Log.d(TAG, "Auto-loaded companion SRT file: ${companionSrt.absolutePath}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Companion SRT auto-detection bypassed or failed: ${e.message}")
            }
        }
    }

    private fun updateSubtitlesForPosition(positionMs: Long) {
        if (!_isSubtitleEnabled.value) {
            _activeSubtitleText.value = ""
            return
        }

        if (_isExternalSrtActive.value) {
            val activeCue = externalCues.firstOrNull { it.isActiveAt(positionMs) }
            _activeSubtitleText.value = activeCue?.text ?: ""
            return
        }

        val currentChunkIndex = positionMs / CHUNK_DURATION_MS
        val cachedText = aiTranscribedCache[currentChunkIndex]

        if (cachedText != null) {
            _activeSubtitleText.value = cachedText
        } else {
            // Keep prior overlay while this chunk is decoding so the UI does not flicker blank.
            _activeSubtitleText.value = ""

            if(isEngineReady) {
                triggerAiTranscriptionForChunk(currentChunkIndex)
                // Prefetch upcoming window so text is ready when playback reaches it.
                for (ahead in 1L..PREFETCH_AHEAD_CHUNKS) {
                    triggerAiTranscriptionForChunk(currentChunkIndex + ahead)
                }
            }
        }
    }

    private fun triggerAiTranscriptionForChunk(chunkIndex: Long) {
        if (!isEngineReady) return
        if (aiTranscribedCache.containsKey(chunkIndex)) return
        if (!ongoingTranscriptions.add(chunkIndex)) return

        viewModelScope.launch {
            try {
                val startTimeMs = chunkIndex * CHUNK_DURATION_MS

                // Log to verify we are requesting different timestamps
                Log.d(TAG, "Starting extraction for Chunk $chunkIndex at ${startTimeMs}ms")

                withContext(Dispatchers.Main) {
                    if (playerController.currentPosition.value / CHUNK_DURATION_MS == chunkIndex) {
                        _statusText.value = "Transcribing ${startTimeMs / 1000}s…"
                    }
                }

                val rawPcm = audioExtractor.extractAudioRange(
                    videoUri = videoUri,
                    startTimeMs = startTimeMs,
                    durationMs = CHUNK_DURATION_MS
                )

                var translatedText : String = ""

                if (rawPcm.isNotEmpty()) {
                val result = transcriptionEngine.transcribeAndTranslate(rawPcm, chunkIndex)

                // Break it to ONLY text here
                 translatedText = result.joinToString(separator = " ") { it.text }.trim()
//                    transcriptionEngine.transcribeAndTranslate(rawPcm, chunkIndex).toString()
                } else {
                    translatedText = "Translating...."
                }

                // Cache successful runs only (including silence). Never cache "not ready" skips.
                if (isEngineReady) {
                    aiTranscribedCache[chunkIndex] = translatedText
                }

                val currentPositionMs = playerController.currentPosition.value
                val activeChunk = currentPositionMs / CHUNK_DURATION_MS
                if (activeChunk == chunkIndex) {
                    _activeSubtitleText.value = translatedText
                    _statusText.value = "AI active"
                }

                Log.d(
                    TAG,
                    "Chunk $chunkIndex ready (active=$activeChunk): \"${translatedText.take(80)}\""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in transcription pipeline for chunk $chunkIndex", e)
                // Do not cache failures — allow retry when this chunk is visited again.
            } finally {
                ongoingTranscriptions.remove(chunkIndex)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerController.release()
        transcriptionEngine.release()
    }
}
