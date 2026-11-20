#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "resampler.h"
#include "transcribe_job.h"
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// whisper.cpp logs to stderr by default, which is invisible on Android.
// Route everything to logcat so failures are actually debuggable.
void log_to_logcat(ggml_log_level level, const char * text, void * /*user_data*/) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    if (text != nullptr) {
        __android_log_write(prio, TAG, text);
    }
}

inline whisper_context * as_context(jlong handle) {
    return reinterpret_cast<whisper_context *>(handle);
}

inline SincResampler * as_resampler(jlong handle) {
    return reinterpret_cast<SincResampler *>(handle);
}

inline TranscribeJob * as_job(jlong handle) {
    return reinterpret_cast<TranscribeJob *>(handle);
}

jfloatArray to_float_array(JNIEnv * env, const std::vector<float> & values) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

} // namespace

#ifndef EKSTRAK_WHISPER_VERSION
#define EKSTRAK_WHISPER_VERSION "unknown"
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeVersion(JNIEnv * env, jobject /*thiz*/) {
    return env->NewStringUTF(EKSTRAK_WHISPER_VERSION);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeSetLogEnabled(JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    whisper_log_set(enabled ? log_to_logcat : nullptr, nullptr);
}

/**
 * Loads a ggml model from disk. Returns 0 on failure.
 *
 * The caller owns the returned handle and must release it with nativeFree().
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring j_model_path) {

    if (j_model_path == nullptr) {
        LOGE("nativeInit: model path is null");
        return 0;
    }

    const char * raw = env->GetStringUTFChars(j_model_path, nullptr);
    if (raw == nullptr) {
        LOGE("nativeInit: OOM while reading model path");
        return 0;
    }
    const std::string model_path(raw);
    env->ReleaseStringUTFChars(j_model_path, raw);

    whisper_log_set(log_to_logcat, nullptr);

    whisper_context_params params = whisper_context_default_params();
    // CPU only. Vulkan/OpenCL backends are not shipped in this build.
    params.use_gpu = false;
    // CPU flash-attention is not implemented for every arch in whisper.cpp 1.7.x;
    // leaving it on produces a warning and a silent fallback. Be explicit.
    params.flash_attn = false;

    LOGI("loading model: %s", model_path.c_str());
    whisper_context * ctx = whisper_init_from_file_with_params(model_path.c_str(), params);

    if (ctx == nullptr) {
        LOGE("nativeInit: whisper_init_from_file_with_params failed for %s", model_path.c_str());
        return 0;
    }

    LOGI("model loaded ok: %s", model_path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    whisper_context * ctx = as_context(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("model freed");
    }
}

// ---------------------------------------------------------------------------
// Resampler
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeResamplerCreate(
        JNIEnv * /*env*/, jobject /*thiz*/, jint inRate, jint outRate, jint channels) {
    if (inRate <= 0 || outRate <= 0 || channels <= 0) {
        LOGE("nativeResamplerCreate: invalid args %d %d %d", inRate, outRate, channels);
        return 0;
    }
    auto * resampler = new SincResampler(inRate, outRate, channels);
    LOGI("resampler %d -> %d Hz, %d ch, %d taps", inRate, outRate, channels, resampler->taps());
    return reinterpret_cast<jlong>(resampler);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeResamplerProcess(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jfloatArray input, jint frames) {

    auto * resampler = as_resampler(handle);
    if (resampler == nullptr || input == nullptr || frames <= 0) {
        return env->NewFloatArray(0);
    }

    const jsize sampleCount = static_cast<jsize>(frames) * resampler->channels();
    std::vector<float> in(static_cast<size_t>(sampleCount));
    env->GetFloatArrayRegion(input, 0, sampleCount, in.data());

    std::vector<float> out;
    out.reserve(static_cast<size_t>(frames) + 64);
    resampler->process(in.data(), static_cast<size_t>(frames), out);

    return to_float_array(env, out);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeResamplerFlush(
        JNIEnv * env, jobject /*thiz*/, jlong handle) {

    auto * resampler = as_resampler(handle);
    if (resampler == nullptr) return env->NewFloatArray(0);

    std::vector<float> out;
    resampler->flush(out);
    return to_float_array(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeResamplerDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    delete as_resampler(handle);
}

// ---------------------------------------------------------------------------
// Transcription job
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobStart(
        JNIEnv * env, jobject /*thiz*/, jlong ctxHandle, jstring j_wav_path, jstring j_language,
        jint threads, jint chunkSeconds) {

    auto * ctx = as_context(ctxHandle);
    if (ctx == nullptr) {
        LOGE("nativeJobStart: null context");
        return 0;
    }

    std::string wavPath;
    std::string language;
    if (j_wav_path != nullptr) {
        const char * raw = env->GetStringUTFChars(j_wav_path, nullptr);
        if (raw != nullptr) {
            wavPath = raw;
            env->ReleaseStringUTFChars(j_wav_path, raw);
        }
    }
    if (j_language != nullptr) {
        const char * raw = env->GetStringUTFChars(j_language, nullptr);
        if (raw != nullptr) {
            language = raw;
            env->ReleaseStringUTFChars(j_language, raw);
        }
    }

    auto * job = new TranscribeJob();
    job->ctx = ctx;
    job->wavPath = wavPath;
    job->language = language.empty() ? "id" : language;
    job->threads = threads > 0 ? threads : 4;
    job->chunkSeconds = chunkSeconds > 0 ? chunkSeconds : 300;
    job->start();

    return reinterpret_cast<jlong>(job);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobProgress(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * job = as_job(handle);
    return job == nullptr ? 0 : job->getProgress();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobDone(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * job = as_job(handle);
    return static_cast<jboolean>(job != nullptr && job->isDone());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobOk(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * job = as_job(handle);
    return static_cast<jboolean>(job != nullptr && job->isOk());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobError(
        JNIEnv * env, jobject /*thiz*/, jlong handle) {
    auto * job = as_job(handle);
    if (job == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(job->getError().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobSegmentCount(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * job = as_job(handle);
    return job == nullptr ? 0 : static_cast<jint>(job->segmentCount());
}

/** Returns "t0|t1|text" with timestamps in milliseconds. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobSegmentAt(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jint index) {
    auto * job = as_job(handle);
    if (job == nullptr || index < 0) return env->NewStringUTF("");
    const std::string value = job->segmentAt(static_cast<size_t>(index));
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobCancel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * job = as_job(handle);
    if (job != nullptr) job->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ekstraktranskrip_core_whisper_NativeBridge_nativeJobFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * job = as_job(handle);
    if (job != nullptr) {
        job->cancel.store(true);
        delete job;  // destructor joins the worker
    }
}
