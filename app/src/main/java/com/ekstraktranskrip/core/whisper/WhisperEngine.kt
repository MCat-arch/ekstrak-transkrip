package com.ekstraktranskrip.core.whisper

import java.io.Closeable

/**
 * Owns exactly one whisper context. Not thread safe by design: one job at a
 * time, always driven from the transcription service.
 */
class WhisperEngine : Closeable {

    @Volatile
    private var ctx: Long = 0L

    @Volatile
    private var loadedPath: String? = null

    val isLoaded: Boolean get() = ctx != 0L

    /** True when this exact file is already in memory (avoids a needless reload). */
    fun isLoadedFor(modelPath: String): Boolean = ctx != 0L && loadedPath == modelPath

    /** Blocking. Returns false when the model cannot be loaded. */
    fun load(modelPath: String): Boolean {
        unload()
        ctx = NativeBridge.nativeInit(modelPath)
        loadedPath = if (ctx != 0L) modelPath else null
        return ctx != 0L
    }

    fun startJob(
        wavPath: String,
        language: String = "id",
        threads: Int = 4,
        chunkSeconds: Int = 600,
    ): TranscriptionJob? {
        val handle = ctx
        if (handle == 0L) return null
        val job = NativeBridge.nativeJobStart(handle, wavPath, language, threads, chunkSeconds)
        return if (job == 0L) null else TranscriptionJob(job)
    }

    fun unload() {
        val handle = ctx
        ctx = 0L
        loadedPath = null
        if (handle != 0L) NativeBridge.nativeFree(handle)
    }

    override fun close() = unload()
}

class TranscriptionJob internal constructor(internal val handle: Long) {

    data class Segment(val t0Ms: Long, val t1Ms: Long, val text: String)

    val progress: Int get() = NativeBridge.nativeJobProgress(handle)
    val isDone: Boolean get() = NativeBridge.nativeJobDone(handle)
    val isOk: Boolean get() = NativeBridge.nativeJobOk(handle)
    val error: String get() = NativeBridge.nativeJobError(handle)
    val segmentCount: Int get() = NativeBridge.nativeJobSegmentCount(handle)

    fun segmentAt(index: Int): Segment? {
        val raw = NativeBridge.nativeJobSegmentAt(handle, index)
        if (raw.isEmpty()) return null
        val first = raw.indexOf('|')
        val second = if (first >= 0) raw.indexOf('|', first + 1) else -1
        if (first <= 0 || second <= first) return null
        val t0 = raw.substring(0, first).toLongOrNull() ?: return null
        val t1 = raw.substring(first + 1, second).toLongOrNull() ?: return null
        val text = raw.substring(second + 1)
        if (text.isBlank()) return null
        return Segment(t0, t1, text)
    }

    fun cancel() = NativeBridge.nativeJobCancel(handle)

    fun release() = NativeBridge.nativeJobFree(handle)
}
