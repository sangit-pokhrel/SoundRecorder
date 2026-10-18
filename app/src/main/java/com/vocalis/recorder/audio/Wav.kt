package com.vocalis.recorder.audio

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Streams 16-bit PCM WAV. The header is rewritten periodically so a recording interrupted by a
 * crash or a dead battery is still a playable file.
 */
class WavWriter(file: File, val sampleRate: Int, val channels: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var bytesSinceHeader = 0L

    val framesWritten: Long get() = dataBytes / (2 * channels)

    init {
        raf.setLength(0)
        writeHeader()
    }

    /** Write interleaved float samples in [-1, 1]. */
    fun write(samples: FloatArray, offset: Int, count: Int) {
        if (buffer.capacity() < count * 2) {
            buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        buffer.clear()
        for (i in offset until offset + count) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            buffer.putShort(v.toShort())
        }
        raf.write(buffer.array(), 0, buffer.position())
        dataBytes += buffer.position()
        bytesSinceHeader += buffer.position()
        if (bytesSinceHeader > sampleRate * channels * 2L * HEADER_REFRESH_SECONDS) {
            bytesSinceHeader = 0
            val pos = raf.filePointer
            writeHeader()
            raf.seek(pos)
        }
    }

    override fun close() {
        writeHeader()
        raf.close()
    }

    private fun writeHeader() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        h.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
    }

    private companion object {
        const val HEADER_REFRESH_SECONDS = 2
    }
}

/** Reads PCM WAV files (16/24/32-bit integer and 32-bit float) as interleaved floats. */
class WavReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val channels: Int
    private val bitsPerSample: Int
    private val isFloat: Boolean
    private val dataStart: Long
    private val dataEnd: Long
    private var bytes = ByteArray(0)

    val totalFrames: Long
    val durationMs: Long get() = totalFrames * 1000 / sampleRate

    init {
        try {
            val riff = readTag()
            raf.skipBytes(4)
            val wave = readTag()
            if (riff != "RIFF" || wave != "WAVE") throw IOException("Not a WAV file")
            var fmtFound = false
            var sr = 0; var ch = 0; var bits = 0; var fmt = 0
            var start = -1L; var size = 0L
            while (raf.filePointer + 8 <= raf.length()) {
                val id = readTag()
                val len = readIntLe().toLong() and 0xFFFFFFFFL
                val chunkStart = raf.filePointer
                when (id) {
                    "fmt " -> {
                        fmt = readShortLe()
                        ch = readShortLe()
                        sr = readIntLe()
                        raf.skipBytes(6)
                        bits = readShortLe()
                        if (fmt == 0xFFFE && len >= 26) { // WAVE_FORMAT_EXTENSIBLE: real format in sub-GUID
                            raf.skipBytes(8)
                            fmt = readShortLe()
                        }
                        fmtFound = true
                    }
                    "data" -> {
                        start = chunkStart
                        val toEnd = raf.length() - chunkStart
                        // A take interrupted before close() has a stale (or zero) size in its
                        // header but audio up to the end of the file. Trust the header only if
                        // it fits and is followed by another real chunk.
                        size = if (len == 0L || len > toEnd || (len < toEnd && !chunkFollows(chunkStart + len))) toEnd else len
                        break
                    }
                }
                raf.seek(chunkStart + len + (len and 1))
            }
            if (!fmtFound || start < 0) throw IOException("Missing fmt or data chunk")
            if (fmt != 1 && fmt != 3) throw IOException("Unsupported WAV encoding $fmt")
            if (bits !in setOf(16, 24, 32)) throw IOException("Unsupported bit depth $bits")
            sampleRate = sr
            channels = ch
            bitsPerSample = bits
            isFloat = fmt == 3
            dataStart = start
            val frameBytes = ch * bits / 8
            totalFrames = size / frameBytes
            dataEnd = start + totalFrames * frameBytes
            raf.seek(dataStart)
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    fun seekToFrame(frame: Long) {
        raf.seek(dataStart + frame.coerceIn(0, totalFrames) * channels * bitsPerSample / 8)
    }

    /** Reads up to [maxFrames] interleaved frames into [out]; returns frames read, or 0 at end. */
    fun read(out: FloatArray, maxFrames: Int): Int {
        val bytesPerSample = bitsPerSample / 8
        val frameBytes = channels * bytesPerSample
        val remaining = ((dataEnd - raf.filePointer) / frameBytes).toInt().coerceAtLeast(0)
        val frames = minOf(maxFrames, remaining, out.size / channels)
        if (frames == 0) return 0
        val need = frames * frameBytes
        if (bytes.size < need) bytes = ByteArray(need)
        raf.readFully(bytes, 0, need)
        val bb = ByteBuffer.wrap(bytes, 0, need).order(ByteOrder.LITTLE_ENDIAN)
        val samples = frames * channels
        when {
            isFloat -> for (i in 0 until samples) out[i] = bb.getFloat()
            bitsPerSample == 16 -> for (i in 0 until samples) out[i] = bb.getShort() / 32768f
            bitsPerSample == 24 -> for (i in 0 until samples) {
                val v = (bb.get().toInt() and 0xFF) or ((bb.get().toInt() and 0xFF) shl 8) or (bb.get().toInt() shl 16)
                out[i] = v / 8388608f
            }
            else -> for (i in 0 until samples) out[i] = bb.getInt() / 2147483648f
        }
        return frames
    }

    override fun close() = raf.close()

    /** True if a valid-looking RIFF chunk header (4 printable ASCII chars) starts at [pos] or [pos]+1. */
    private fun chunkFollows(pos: Long): Boolean {
        val saved = raf.filePointer
        try {
            for (p in longArrayOf(pos, pos + 1)) {
                if (p + 8 > raf.length()) continue
                raf.seek(p)
                val id = ByteArray(4).also { raf.readFully(it) }
                if (id.all { it in 0x20..0x7E }) return true
            }
            return false
        } finally {
            raf.seek(saved)
        }
    }

    private fun readTag(): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun readIntLe(): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readShortLe(): Int {
        val lo = raf.read()
        val hi = raf.read()
        if (hi < 0) throw EOFException()
        return lo or (hi shl 8)
    }
}
