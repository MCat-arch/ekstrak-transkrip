package com.ekstraktranskrip.core.whisper

/**
 * Thin, allocation-free JNI boundary.
 *
 * Keep this file boring: every method here is called from a single background
 * dispatcher, no shared mutable state lives here.
 */
object NativeBridge {

    /** Null when the .so loaded fine, otherwise the failure reason. */
    val loadError: String? = try {
        System.loadLibrary("whisper_jni")
        null
    } catch (t: Throwable) {
        t.message ?: t::class.java.simpleName
    }

    val isLoaded: Boolean get() = loadError == null

    /** Version string compiled into the bundled whisper.cpp. */
    external fun nativeVersion(): String

    /** Toggle whisper.cpp -> logcat routing. */
    external fun nativeSetLogEnabled(enabled: Boolean)

    /**
     * Loads a ggml model. Returns 0 when the file is missing/corrupt/unsupported.
     * Blocking call: never run this on the main thread.
     */
    external fun nativeInit(modelPath: String): Long

    /** Releases a handle returned by [nativeInit]. Safe to call with 0. */
    external fun nativeFree(handle: Long)

    // -----------------------------------------------------------------------
    // Resampler (native, band-limited sinc)
    // -----------------------------------------------------------------------

    external fun nativeResamplerCreate(inRate: Int, outRate: Int, channels: Int): Long

    /** Returns interleaved mono/stereo frames resampled from [input]. */
    external fun nativeResamplerProcess(handle: Long, input: FloatArray, frames: Int): FloatArray

    external fun nativeResamplerFlush(handle: Long): FloatArray

    external fun nativeResamplerDestroy(handle: Long)

    // -----------------------------------------------------------------------
    // Transcription job (runs on its own native thread, polled from Kotlin)
    // -----------------------------------------------------------------------

    external fun nativeJobStart(
        ctxHandle: Long,
        wavPath: String,
        language: String,
        threads: Int,
        chunkSeconds: Int,
    ): Long

    external fun nativeJobProgress(job: Long): Int
    external fun nativeJobDone(job: Long): Boolean
    external fun nativeJobOk(job: Long): Boolean
    external fun nativeJobError(job: Long): String
    external fun nativeJobSegmentCount(job: Long): Int

    /** "t0|t1|text", timestamps in milliseconds. */
    external fun nativeJobSegmentAt(job: Long, index: Int): String
    external fun nativeJobCancel(job: Long)
    external fun nativeJobFree(job: Long)
}
