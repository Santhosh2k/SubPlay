package com.example.offlinesubtitleplayer.domain.transcription

interface TranscriptionEngine {
    /**
     * Initializes the engine resources, loading models if needed.
     */
    suspend fun initialize(): Boolean

    /**
     * Transcribes a raw PCM audio chunk (16kHz, mono, float) into timed segments.
     * Offsets are relative to the start of [pcmData].
     */
    suspend fun transcribeAndTranslate(
        pcmData: FloatArray,
        chunkIndex: Long = 0L
    ): List<TranscriptSegment>

    /**
     * Releases resource handles.
     */
    fun release()
}
