// JNI bridge between WhisperTranscriptionEngineImpl.kt and whisper.cpp.
//
// transcribeNative returns timed segments, one per line:
//   startMs\tendMs\ttext
// Times are relative to the start of the PCM buffer (milliseconds).

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlinesubtitleplayer_data_transcription_WhisperTranscriptionEngineImpl_initNativeWhisper(
        JNIEnv *env, jobject /* this */, jint fd, jlong offset, jlong length) {

    if (length <= 0) {
        LOGE("Invalid asset length: %lld", (long long) length);
        return 0;
    }

    std::vector<uint8_t> buffer(static_cast<size_t>(length));

    if (lseek(fd, offset, SEEK_SET) < 0) {
        LOGE("lseek failed on model fd");
        return 0;
    }

    size_t totalRead = 0;
    while (totalRead < buffer.size()) {
        ssize_t r = read(fd, buffer.data() + totalRead, buffer.size() - totalRead);
        if (r <= 0) break;
        totalRead += static_cast<size_t>(r);
    }

    if (totalRead != buffer.size()) {
        LOGE("Short read loading model: got %zu of %zu bytes", totalRead, buffer.size());
        return 0;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx =
            whisper_init_from_buffer_with_params(buffer.data(), buffer.size(), cparams);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_buffer_with_params failed");
        return 0;
    }

    LOGI("Whisper model loaded successfully (%zu bytes)", buffer.size());
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinesubtitleplayer_data_transcription_WhisperTranscriptionEngineImpl_transcribeNative(
        JNIEnv *env, jobject /* this */, jlong contextPtr, jfloatArray pcmData, jboolean translate) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize numSamples = env->GetArrayLength(pcmData);
    if (numSamples <= 0) {
        return env->NewStringUTF("");
    }

    std::vector<float> samples(numSamples);
    env->GetFloatArrayRegion(pcmData, 0, numSamples, samples.data());

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    // Multiple timed segments so Kotlin can sync each line to the playhead.
    params.single_segment   = false;
    params.translate        = (translate == JNI_TRUE);
    params.language         = "en";
    params.n_threads        = 4;
    params.no_context       = true;

    int result = whisper_full(ctx, params, samples.data(), numSamples);
    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
        return env->NewStringUTF("");
    }

    // Format: startMs\tendMs\ttext\n  (whisper t0/t1 are in centiseconds)
    std::string output;
    const int numSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < numSegments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;

        // Trim leading whitespace common in whisper segment text
        while (*text == ' ' || *text == '\t') text++;
        if (*text == '\0') continue;

        const int64_t t0_ms = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1_ms = whisper_full_get_segment_t1(ctx, i) * 10;

        output += std::to_string(t0_ms);
        output += '\t';
        output += std::to_string(t1_ms);
        output += '\t';
        output += text;
        output += '\n';
    }

    LOGI("Transcribed %d segment(s)", numSegments);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlinesubtitleplayer_data_transcription_WhisperTranscriptionEngineImpl_freeNativeWhisper(
        JNIEnv *env, jobject /* this */, jlong contextPtr) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Whisper context released");
    }
}
