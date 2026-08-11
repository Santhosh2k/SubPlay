package com.example.offlinesubtitleplayer.data.transcription

import android.util.Log
import com.example.offlinesubtitleplayer.domain.transcription.TranscriptSegment
import com.example.offlinesubtitleplayer.domain.transcription.TranscriptionEngine
import kotlinx.coroutines.delay
import kotlin.math.sqrt

class SimulatedTranscriptionEngine : TranscriptionEngine {

    companion object {
        private const val TAG = "SimulatedTransEngine"
        private const val ENERGY_THRESHOLD = 0.005f
    }

    private var isInitialized = false

    private val englishSubtitles = listOf(
        "This is a Test Message",
        "Trying to check some cases",
        "Welcome to offline transcription.",
        "Audio is processed chunk by chunk.",
        "Subtitles stay in sync with playback."
    )

    override suspend fun initialize(): Boolean {
        if (isInitialized) return true
        Log.d(TAG, "Initializing simulated transcription engine...")
        delay(500)
        isInitialized = true
        return true
    }

    override suspend fun transcribeAndTranslate(
        pcmData: FloatArray,
        chunkIndex: Long
    ): List<TranscriptSegment> {
        if (!isInitialized) {
            throw IllegalStateException("TranscriptionEngine not initialized!")
        }
        if (pcmData.isEmpty()) return emptyList()

        var sum = 0.0f
        for (sample in pcmData) sum += sample * sample
        val rms = sqrt(sum / pcmData.size)
        if (rms < ENERGY_THRESHOLD) return emptyList()

        delay(150)

        val index = (chunkIndex % englishSubtitles.size).toInt()
        val text = englishSubtitles[index]
        // Cover most of a typical chunk so the overlay stays visible while playing.
        return listOf(TranscriptSegment(0L, 2800L, text))
    }

    override fun release() {
        isInitialized = false
    }
}
