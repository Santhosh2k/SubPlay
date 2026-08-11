package com.example.offlinesubtitleplayer.data.transcription

import android.content.Context
import android.util.Log
import com.example.offlinesubtitleplayer.domain.transcription.TranscriptSegment
import com.example.offlinesubtitleplayer.domain.transcription.TranscriptionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device Whisper engine, backed by whisper.cpp via JNI.
 * See app/src/main/cpp/whisper_jni.cpp for the native side.
 */
class WhisperTranscriptionEngineImpl(
    private val context: Context,
    private val modelAssetPath: String = "ggml-tiny.en.bin"
) : TranscriptionEngine {

    private val mutex = Mutex()

    companion object {
        private const val TAG = "WhisperEngineImpl"
        private var nativeLibLoaded = false

        init {
            try {
                System.loadLibrary("whisper")
                nativeLibLoaded = true
                Log.i(TAG, "Native whisper library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libwhisper.so — did the NDK build run? (${e.message})")
            }
        }

        /**
         * Parses native output lines: startMs\tendMs\ttext
         */
        fun parseTimedSegments(raw: String): List<TranscriptSegment> {
            if (raw.isBlank()) return emptyList()
            return raw.lineSequence().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val start = parts[0].toLongOrNull() ?: return@mapNotNull null
                val end = parts[1].toLongOrNull() ?: return@mapNotNull null
                val text = parts[2].trim()
                if (text.isEmpty()) return@mapNotNull null
                val safeEnd = end.coerceAtLeast(start + 200L)
                TranscriptSegment(start, safeEnd, text)
            }.toList()
        }
    }

    private var nativeContextPointer: Long = 0

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (!nativeLibLoaded) {
            Log.e(TAG, "Native library not loaded, cannot initialize whisper.")
            return@withContext false
        }
        try {
            Log.d(TAG, "Loading model from assets: $modelAssetPath")
            val fileDescriptor = context.assets.openFd(modelAssetPath)

            nativeContextPointer = initNativeWhisper(
                fileDescriptor.parcelFileDescriptor.fd,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )

            fileDescriptor.close()

            val success = nativeContextPointer != 0L
            Log.d(TAG, "Whisper native initialization status: $success")
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load whisper model asset", e)
            return@withContext false
        }
    }

    override suspend fun transcribeAndTranslate(
        pcmData: FloatArray,
        chunkIndex: Long
    ): List<TranscriptSegment> = withContext(Dispatchers.Default) {
        if (nativeContextPointer == 0L) {
            Log.w(TAG, "Native context not initialized, skipping chunk $chunkIndex")
            return@withContext emptyList()
        }

        mutex.withLock {
            Log.d(TAG, "Transcribing chunk $chunkIndex (${pcmData.size} samples)")
            // ggml-tiny.en.bin is English ASR only.
            val raw = transcribeNative(nativeContextPointer, pcmData, false)
            val segments = parseTimedSegments(raw)
            Log.d(TAG, "Chunk $chunkIndex produced ${segments.size} segment(s)")
            return@withContext segments
        }
    }

    override fun release() {
        if (nativeContextPointer != 0L) {
            freeNativeWhisper(nativeContextPointer)
            nativeContextPointer = 0L
            Log.d(TAG, "Native whisper resources freed.")
        }
    }

    private external fun initNativeWhisper(fd: Int, offset: Long, length: Long): Long

    private external fun transcribeNative(
        contextPointer: Long,
        pcmData: FloatArray,
        translate: Boolean
    ): String

    private external fun freeNativeWhisper(contextPointer: Long)
}
