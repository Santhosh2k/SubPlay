package com.example.offlinesubtitleplayer.domain.transcription

/**
 * One timed speech span relative to the start of the transcribed PCM window.
 */
data class TranscriptSegment(
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val text: String
)
