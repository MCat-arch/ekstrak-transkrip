package com.ekstraktranskrip.core.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Minimal 16-bit PCM WAV writer.
 *
 * Header sizes are patched on close, so we can stream without knowing the
 * final length (a 2 h file would otherwise need a 230 MB buffer).
 */
class WavWriter(
    val file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0
    private var closed = false

    private val byteBuffer = ByteArray(BUFFER_SAMPLES * 2)
    private var byteBufferPos = 0

    val samplesWritten: Long get() = dataBytes / 2

    init {
        require(channels in 1..2) { "channels must be 1 or 2" }
        raf.setLength(0)
        raf.write(ByteArray(HEADER_SIZE))
    }

    /** Writes `count` mono (or interleaved) float samples, clipping to [-1, 1]. */
    fun writeFloats(values: FloatArray, count: Int = values.size) {
        var i = 0
        while (i < count) {
            if (byteBufferPos >= byteBuffer.size) flushBuffer()
            val v = values[i]
            val s = (v.coerceIn(-1f, 1f) * 32767f).roundToInt()
            byteBuffer[byteBufferPos++] = (s and 0xFF).toByte()
            byteBuffer[byteBufferPos++] = ((s shr 8) and 0xFF).toByte()
            i++
        }
        dataBytes += count.toLong() * 2L
    }

    fun writeShorts(values: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            if (byteBufferPos >= byteBuffer.size) flushBuffer()
            val s = values[i].toInt()
            byteBuffer[byteBufferPos++] = (s and 0xFF).toByte()
            byteBuffer[byteBufferPos++] = ((s shr 8) and 0xFF).toByte()
            i++
        }
        dataBytes += count.toLong() * 2L
    }

    fun durationMs(): Long = if (sampleRate <= 0) 0 else (samplesWritten * 1000) / sampleRate

    override fun close() {
        if (closed) return
        closed = true
        flushBuffer()
        patchHeader()
        raf.close()
    }

    private fun flushBuffer() {
        if (byteBufferPos == 0) return
        raf.write(byteBuffer, 0, byteBufferPos)
        byteBufferPos = 0
    }

    private fun patchHeader() {
        val dataSize = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2

        raf.seek(0)
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.writeLe32(36 + dataSize)
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.writeLe32(16)              // PCM chunk size
        raf.writeLe16(1)               // PCM format
        raf.writeLe16(channels)
        raf.writeLe32(sampleRate)
        raf.writeLe32(byteRate)
        raf.writeLe16(blockAlign)
        raf.writeLe16(16)              // bits per sample
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.writeLe32(dataSize)
        raf.seek(HEADER_SIZE + dataBytes)
    }

    private fun RandomAccessFile.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    companion object {
        const val HEADER_SIZE = 44
        private const val BUFFER_SAMPLES = 8192
    }
}
