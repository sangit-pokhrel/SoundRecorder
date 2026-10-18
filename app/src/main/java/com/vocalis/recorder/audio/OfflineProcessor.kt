package com.vocalis.recorder.audio

import com.vocalis.recorder.dsp.EffectSettings
import com.vocalis.recorder.dsp.Limiter
import com.vocalis.recorder.dsp.LoudnessMeter
import com.vocalis.recorder.dsp.ProcessingChain
import com.vocalis.recorder.dsp.dbToLinear
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** File-to-file rendering: re-processing existing recordings and loudness normalization. */
object OfflineProcessor {
    private const val BLOCK = 4096

    /** Never boost quieter recordings by more than this; it would only amplify the noise floor. */
    private const val MAX_GAIN_DB = 24f

    /**
     * Runs the full chain over [input] and writes [output]. If normalization is enabled the
     * result is brought to the target loudness in a second pass.
     */
    fun enhance(
        input: File,
        output: File,
        settings: EffectSettings,
        onProgress: (Float) -> Unit = {},
    ) {
        val temp = File(output.parentFile, ".${output.name}.f32")
        try {
            val meter: LoudnessMeter
            val sampleRate: Int
            val channels: Int
            WavReader(input).use { reader ->
                sampleRate = reader.sampleRate
                channels = reader.channels
                meter = LoudnessMeter(sampleRate, channels)
                val chain = ProcessingChain(sampleRate, channels, settings)
                val total = reader.totalFrames.coerceAtLeast(1)
                FloatFileWriter(temp).use { sink ->
                    renderWithLatency(reader, chain.latencySamples, channels, chain::process) { buf, frames, done ->
                        meter.add(buf, frames)
                        sink.write(buf, frames * channels)
                        onProgress(0.5f * done / total)
                    }
                }
            }
            val gainDb = if (settings.normalizeEnabled) normalizationGain(meter, settings.normalizeTargetLufs) else 0f
            val ceiling = if (settings.limiterEnabled) settings.limiterCeilingDb else -0.1f
            finalPass(temp, output, sampleRate, channels, gainDb, ceiling) { onProgress(0.5f + 0.5f * it) }
        } finally {
            temp.delete()
        }
    }

    /** Measures [file] and rewrites it in place at [targetLufs] with a peak ceiling. */
    fun normalize(file: File, targetLufs: Float, ceilingDb: Float, onProgress: (Float) -> Unit = {}) {
        val sampleRate: Int
        val channels: Int
        val meter: LoudnessMeter
        WavReader(file).use { reader ->
            sampleRate = reader.sampleRate
            channels = reader.channels
            meter = LoudnessMeter(sampleRate, channels)
            val buf = FloatArray(BLOCK * channels)
            val total = reader.totalFrames.coerceAtLeast(1)
            var done = 0L
            while (true) {
                val n = reader.read(buf, BLOCK)
                if (n == 0) break
                meter.add(buf, n)
                done += n
                onProgress(0.4f * done / total)
            }
        }
        val gainDb = normalizationGain(meter, targetLufs)
        val temp = File(file.parentFile, ".${file.name}.tmp")
        try {
            WavReader(file).use { reader ->
                WavWriter(temp, sampleRate, channels).use { writer ->
                    val limiters = Array(channels) { Limiter(sampleRate).apply { this.ceilingDb = ceilingDb } }
                    val gain = dbToLinear(gainDb)
                    val total = reader.totalFrames.coerceAtLeast(1)
                    renderWithLatency(reader, limiters[0].latencySamples, channels, { buf, frames ->
                        for (i in 0 until frames * channels) buf[i] *= gain
                        applyPerChannel(buf, frames, channels, limiters)
                    }) { buf, frames, done ->
                        writer.write(buf, 0, frames * channels)
                        onProgress(0.4f + 0.6f * done / total)
                    }
                }
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun normalizationGain(meter: LoudnessMeter, targetLufs: Float): Float {
        val measured = meter.integratedLufs() ?: return 0f
        return (targetLufs - measured).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
    }

    private fun finalPass(
        temp: File,
        output: File,
        sampleRate: Int,
        channels: Int,
        gainDb: Float,
        ceilingDb: Float,
        onProgress: (Float) -> Unit,
    ) {
        val gain = dbToLinear(gainDb)
        val limiters = Array(channels) { Limiter(sampleRate).apply { this.ceilingDb = ceilingDb } }
        val latency = limiters[0].latencySamples
        val totalFrames = (temp.length() / 4 / channels).coerceAtLeast(1)
        FloatFileReader(temp).use { source ->
            WavWriter(output, sampleRate, channels).use { writer ->
                val buf = FloatArray(BLOCK * channels)
                var toSkip = latency
                var done = 0L
                var flushLeft = latency
                while (true) {
                    var frames = source.read(buf, BLOCK * channels) / channels
                    if (frames == 0) {
                        if (flushLeft == 0) break
                        frames = minOf(flushLeft, BLOCK)
                        buf.fill(0f, 0, frames * channels)
                        flushLeft -= frames
                    } else {
                        done += frames
                    }
                    for (i in 0 until frames * channels) buf[i] *= gain
                    applyPerChannel(buf, frames, channels, limiters)
                    val skip = minOf(toSkip, frames)
                    toSkip -= skip
                    if (frames > skip) writer.write(buf, skip * channels, (frames - skip) * channels)
                    onProgress(done.toFloat() / totalFrames)
                }
            }
        }
    }

    private fun applyPerChannel(buf: FloatArray, frames: Int, channels: Int, limiters: Array<Limiter>) {
        if (channels == 1) {
            limiters[0].process(buf, 0, frames)
            return
        }
        val mono = FloatArray(frames)
        for (c in 0 until channels) {
            for (f in 0 until frames) mono[f] = buf[f * channels + c]
            limiters[c].process(mono, 0, frames)
            for (f in 0 until frames) buf[f * channels + c] = mono[f]
        }
    }

    /**
     * Pulls blocks from [reader] through [process], dropping the first [latency] output frames
     * and flushing [latency] frames of silence at the end so output lines up with input.
     */
    private inline fun renderWithLatency(
        reader: WavReader,
        latency: Int,
        channels: Int,
        process: (FloatArray, Int) -> Unit,
        sink: (buf: FloatArray, frames: Int, framesDone: Long) -> Unit,
    ) {
        val buf = FloatArray(BLOCK * channels)
        val out = FloatArray(BLOCK * channels)
        var toSkip = latency
        var flushLeft = latency
        var done = 0L
        while (true) {
            var frames = reader.read(buf, BLOCK)
            if (frames == 0) {
                if (flushLeft == 0) break
                frames = minOf(flushLeft, BLOCK)
                buf.fill(0f, 0, frames * channels)
                flushLeft -= frames
            } else {
                done += frames
            }
            process(buf, frames)
            val skip = minOf(toSkip, frames)
            toSkip -= skip
            val keep = frames - skip
            if (keep > 0) {
                System.arraycopy(buf, skip * channels, out, 0, keep * channels)
                sink(out, keep, done)
            }
        }
    }

    private class FloatFileWriter(file: File) : AutoCloseable {
        private val stream = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        private var bb = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

        fun write(data: FloatArray, count: Int) {
            if (bb.capacity() < count * 4) bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.clear()
            for (i in 0 until count) bb.putFloat(data[i])
            stream.write(bb.array(), 0, count * 4)
        }

        override fun close() = stream.close()
    }

    private class FloatFileReader(file: File) : AutoCloseable {
        private val stream = DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16))
        private var bytes = ByteArray(0)

        /** Returns the number of floats read (0 at end of file). */
        fun read(out: FloatArray, maxCount: Int): Int {
            if (bytes.size < maxCount * 4) bytes = ByteArray(maxCount * 4)
            var got = 0
            while (got < maxCount * 4) {
                val r = stream.read(bytes, got, maxCount * 4 - got)
                if (r < 0) break
                got += r
            }
            val count = got / 4
            val bb = ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) out[i] = bb.getFloat()
            return count
        }

        override fun close() = stream.close()
    }
}
