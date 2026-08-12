package com.example.offlinesubtitleplayer.data.extractor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

class AudioExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AudioExtractor"
        private const val TARGET_SAMPLE_RATE = 16000
    }

    /**
     * Extracts and decodes audio from a video file into raw PCM FloatArray (16kHz, mono).
     * Decodes the audio for a specific time range to optimize memory and processing.
     *
     * @param videoUri       The Uri of the local video file.
     * @param startTimeMs    Start time of the window in milliseconds.
     * @param durationMs     Duration of the window to extract in milliseconds.
     * @return FloatArray containing raw audio samples scaled from -1.0 to 1.0.
     */
    suspend fun extractAudioRange(
        videoUri: Uri,
        startTimeMs: Long,
        durationMs: Long
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, videoUri, null)

            // ── Find audio track ──────────────────────────────────────────────
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                Log.e(TAG, "No audio track found in video")
                return@withContext FloatArray(0)
            }

            extractor.selectTrack(audioTrackIndex)

            val startTimeUs = startTimeMs * 1000L
            val endTimeUs   = startTimeUs + (durationMs * 1000L)
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext FloatArray(0)

            // ── Set up MediaCodec decoder ─────────────────────────────────────
            val activeCodec = MediaCodec.createDecoderByType(mime)
            codec = activeCodec
            activeCodec.configure(format, null, null, 0)
            activeCodec.start()

            val info               = MediaCodec.BufferInfo()
            var isExtractorDone    = false
            var isDecoderDone      = false
            val pcmDataCollector   = mutableListOf<Short>()

            val inputChannelCount  = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val inputSampleRate    = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            // ── Decode loop ───────────────────────────────────────────────────
            while (!isDecoderDone) {
                // Feed compressed data into the codec
                if (!isExtractorDone) {
                    val inputBufferIndex = activeCodec.dequeueInputBuffer(10_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = activeCodec.getInputBuffer(inputBufferIndex)!!
                        inputBuffer.clear()

                        val sampleSize  = extractor.readSampleData(inputBuffer, 0)
                        val sampleTimeUs = extractor.sampleTime

                        if (sampleSize < 0 || sampleTimeUs > endTimeUs) {
                            activeCodec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isExtractorDone = true
                        } else {
                            activeCodec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize, sampleTimeUs, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Drain decoded PCM output
                val outputBufferIndex = activeCodec.dequeueOutputBuffer(info, 10_000L)
                if (outputBufferIndex >= 0) {
                    // SEEK_TO_CLOSEST_SYNC can land before startTimeUs — drop early frames
                    // so the PCM window lines up with the video playhead.
                    if (info.size > 0 && info.presentationTimeUs >= startTimeUs) {
                        val outputBuffer = activeCodec.getOutputBuffer(outputBufferIndex)!!
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)

                        val pcmShorts = ShortArray(info.size / 2)
                        outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcmShorts)
                        pcmShorts.forEach { pcmDataCollector.add(it) }
                    }

                    activeCodec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderDone = true
                    }
                }
            }

            // ── Post-process: mix to mono, resample, normalize ─────────────
            val rawPcm       = pcmDataCollector.toShortArray()
            val monoPcm      = mixToMono(rawPcm, inputChannelCount)
            val resampledPcm = resampleTo16kHz(monoPcm, inputSampleRate)

            FloatArray(resampledPcm.size) { i -> resampledPcm[i] / 32_768.0f }

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio: ${e.message}", e)
            FloatArray(0)
        } finally {
            try { codec?.stop();    } catch (_: Exception) {}
            try { codec?.release(); } catch (_: Exception) {}
            extractor.release()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mixToMono(pcmData: ShortArray, channelCount: Int): ShortArray {
        if (channelCount <= 1) return pcmData
        val monoLength = pcmData.size / channelCount
        return ShortArray(monoLength) { i ->
            var sum = 0
            for (c in 0 until channelCount) sum += pcmData[i * channelCount + c]
            (sum / channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun resampleTo16kHz(pcmData: ShortArray, sourceSampleRate: Int): ShortArray {
        if (sourceSampleRate == TARGET_SAMPLE_RATE) return pcmData
        val ratio        = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        val targetLength = (pcmData.size / ratio).toInt()
        return ShortArray(targetLength) { i ->
            val idx = (i * ratio).toInt()
            if (idx < pcmData.size) pcmData[idx] else 0
        }
    }
}
