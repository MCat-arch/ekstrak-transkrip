package com.ekstraktranskrip.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.ekstraktranskrip.core.whisper.NativeBridge
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

class AudioDecodeException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Decodes any audio container the OS can demux into 16 kHz mono PCM16 WAV.
 *
 * Pipeline:
 *   MediaExtractor -> MediaCodec (PCM 16/float/8) -> downmix to mono float
 *   -> SincResampler (native) -> WAV
 *
 * Everything is streamed; a 2 hour file never sits in memory.
 */
class AudioDecoder(private val context: Context) {

    data class Result(
        val wavFile: File,
        val durationMs: Long,
        val containerDurationMs: Long,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val rms: Float,
        val elapsedMs: Long,
    )

    fun decode(
        uri: Uri,
        outFile: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): Result = decodeInternal(
        setup = { it.setDataSource(context, uri, null) },
        outFile = outFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
    )

    /** Same pipeline, for a file already copied into app storage. */
    fun decodeFile(
        path: String,
        outFile: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): Result = decodeInternal(
        setup = { it.setDataSource(path) },
        outFile = outFile,
        isCancelled = isCancelled,
        onProgress = onProgress,
    )

    private fun decodeInternal(
        setup: (MediaExtractor) -> Unit,
        outFile: File,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Result {
        val startedAt = SystemClock.elapsedRealtime()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var resampler = 0L
        var writer: WavWriter? = null
        var sourceRate = 0
        var sourceChannels = 0
        var containerDurationUs = 0L

        try {
            try {
                setup(extractor)
            } catch (t: Throwable) {
                throw AudioDecodeException(
                    "UNREADABLE",
                    "File tidak bisa dibuka. Mungkin corrupt atau bukan file audio.",
                    t,
                )
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                mime != null && mime.startsWith("audio/")
            } ?: throw AudioDecodeException("NO_AUDIO_TRACK", "File ini tidak punya track audio.")

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"

            if (mime.contains("midi", ignoreCase = true)) {
                throw AudioDecodeException(
                    "UNSUPPORTED_CODEC",
                    "MIDI bukan audio nyata, tidak bisa ditranskrip.",
                )
            }

            sourceRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            sourceChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
            containerDurationUs = format.longOr(MediaFormat.KEY_DURATION, 0L)

            if (sourceRate <= 0 || sourceChannels <= 0) {
                throw AudioDecodeException(
                    "UNSUPPORTED_CODEC",
                    "Metadata audio tidak lengkap (sample rate/channel tidak diketahui).",
                )
            }

            extractor.selectTrack(trackIndex)

            val createdCodec = try {
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(format, null, null, 0)
                    it.start()
                }
            } catch (t: Throwable) {
                throw AudioDecodeException(
                    "UNSUPPORTED_CODEC",
                    "Codec $mime tidak didukung di HP ini. Coba convert ke m4a/mp3 dulu.",
                    t,
                )
            }
            codec = createdCodec
            val decoder = createdCodec

            outFile.parentFile?.mkdirs()
            val wav = WavWriter(outFile, SAMPLE_RATE, 1).also { writer = it }

            resampler = createResamplerIfNeeded(sourceRate)

            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var outChannels = sourceChannels
            var outRate = sourceRate

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            var sumSquares = 0.0
            var sampleCount = 0L
            var lastReportAt = 0L
            var lastProgress = -1f

            while (!outputDone) {
                if (isCancelled()) {
                    throw AudioDecodeException("CANCELLED", "Dibatalkan.")
                }
                if (Thread.currentThread().isInterrupted) {
                    throw AudioDecodeException("CANCELLED", "Dibatalkan.")
                }

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        val size = if (inBuf == null) -1 else extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        outChannels = outFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, outChannels)
                        outRate = outFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, outRate)
                        pcmEncoding = outFormat.intOr(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        if (outRate != sourceRate || outChannels != sourceChannels) {
                            // Some decoders (e.g. HE-AAC) only reveal the real rate here.
                            sourceRate = outRate
                            sourceChannels = outChannels
                            if (resampler != 0L) {
                                NativeBridge.nativeResamplerDestroy(resampler)
                            }
                            resampler = createResamplerIfNeeded(outRate)
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    else -> {
                        if (outIndex >= 0) {
                            val buf = decoder.getOutputBuffer(outIndex)
                            if (buf != null && bufferInfo.size > 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)

                                val mono = toMonoFloat(buf, pcmEncoding, outChannels)
                                if (mono.isNotEmpty()) {
                                    for (v in mono) {
                                        sumSquares += v.toDouble() * v
                                    }
                                    sampleCount += mono.size

                                    val out = if (resampler != 0L) {
                                        NativeBridge.nativeResamplerProcess(
                                            resampler, mono, mono.size,
                                        )
                                    } else {
                                        mono
                                    }
                                    if (out.isNotEmpty()) wav.writeFloats(out, out.size)
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }

                            if (containerDurationUs > 0) {
                                val p = (bufferInfo.presentationTimeUs.toFloat() / containerDurationUs)
                                    .coerceIn(0f, 0.99f)
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastReportAt > 200 || p - lastProgress > 0.01f) {
                                    lastProgress = p
                                    lastReportAt = now
                                    onProgress(p)
                                }
                            }
                        }
                    }
                }
            }

            if (resampler != 0L) {
                val tail = NativeBridge.nativeResamplerFlush(resampler)
                if (tail.isNotEmpty()) wav.writeFloats(tail, tail.size)
            }

            wav.close()
            writer = null

            val durationMs = wav.durationMs()
            val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount).toFloat() else 0f

            if (durationMs <= 0) {
                throw AudioDecodeException("EMPTY_OUTPUT", "Hasil konversi kosong (0 detik).")
            }
            if (rms < 0.0005f) {
                throw AudioDecodeException(
                    "SILENT",
                    "Audio hampir tanpa suara (RMS ${"%.5f".format(rms)}). Cek file sumbernya.",
                )
            }

            onProgress(1f)
            return Result(
                wavFile = outFile,
                durationMs = durationMs,
                containerDurationMs = containerDurationUs / 1000,
                sourceSampleRate = sourceRate,
                sourceChannels = sourceChannels,
                rms = rms,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        } finally {
            if (resampler != 0L) NativeBridge.nativeResamplerDestroy(resampler)
            writer?.runCatching { close() }
            codec?.runCatching { stop() }
            codec?.runCatching { release() }
            extractor.runCatching { release() }
        }
    }

    /**
     * The resampler always runs on mono: downmix happens before this step, so
     * the JNI call reads exactly `frames` floats. Creating it with the source
     * channel count here would make the JNI layer read past the array.
     */
    private fun createResamplerIfNeeded(rate: Int): Long =
        if (rate == SAMPLE_RATE) 0L else NativeBridge.nativeResamplerCreate(rate, SAMPLE_RATE, 1)

    /**
     * Downmixes an interleaved buffer to mono float in [-1, 1].
     * Uses ITU-ish weighting for >2 channels so centre-channel dialogue is not
     * buried by a naive average (5.1 average would lose ~15 dB).
     */
    private fun toMonoFloat(buf: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val frames = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buf.remaining() / 4 / channels
            AudioFormat.ENCODING_PCM_8BIT -> buf.remaining() / channels
            AudioFormat.ENCODING_PCM_16BIT -> buf.remaining() / 2 / channels
            else -> throw AudioDecodeException(
                "UNSUPPORTED_PCM_ENCODING",
                "Format PCM dari codec tidak didukung (encoding=$encoding).",
            )
        }
        if (frames <= 0) return FloatArray(0)

        val out = FloatArray(frames)

        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                if (channels == 1) {
                    fb.get(out, 0, frames)
                } else {
                    val tmp = FloatArray(frames * channels)
                    fb.get(tmp, 0, tmp.size)
                    downmix(tmp, out, frames, channels)
                }
            }

            AudioFormat.ENCODING_PCM_16BIT -> {
                val sb = buf.asShortBuffer()
                val tmp = FloatArray(frames * channels)
                var i = 0
                while (i < tmp.size) {
                    tmp[i] = sb.get(i) / 32768f
                    i++
                }
                downmix(tmp, out, frames, channels)
            }

            else -> { // ENCODING_PCM_8BIT (unsigned)
                val tmp = FloatArray(frames * channels)
                val base = buf.position()
                var i = 0
                while (i < tmp.size) {
                    tmp[i] = ((buf.get(base + i).toInt() and 0xFF) - 128) / 128f
                    i++
                }
                downmix(tmp, out, frames, channels)
            }
        }
        return out
    }

    private fun downmix(src: FloatArray, dst: FloatArray, frames: Int, channels: Int) {
        when (channels) {
            1 -> System.arraycopy(src, 0, dst, 0, frames)
            2 -> {
                var f = 0
                var i = 0
                while (f < frames) {
                    dst[f] = 0.5f * (src[i] + src[i + 1])
                    i += 2
                    f++
                }
            }
            else -> {
                // Order for 5.1 on Android: FL FR FC LFE BL BR.
                val weightCentre = if (channels >= 3) 1.414f else 1f
                val weightLfe = if (channels >= 4) 0.5f else 1f
                var totalWeight = 0f
                for (c in 0 until channels) {
                    totalWeight += when (c) {
                        2 -> weightCentre
                        3 -> weightLfe
                        else -> 1f
                    }
                }
                if (totalWeight <= 0f) totalWeight = channels.toFloat()

                var f = 0
                var i = 0
                while (f < frames) {
                    var acc = 0f
                    for (c in 0 until channels) {
                        val w = when (c) {
                            2 -> weightCentre
                            3 -> weightLfe
                            else -> 1f
                        }
                        acc += src[i + c] * w
                    }
                    dst[f] = acc / totalWeight
                    i += channels
                    f++
                }
            }
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun MediaFormat.longOr(key: String, fallback: Long): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
    }
}
