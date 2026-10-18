package com.vocalis.recorder.dsp

import com.vocalis.recorder.audio.OfflineProcessor
import com.vocalis.recorder.audio.WavReader
import com.vocalis.recorder.audio.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    private val sr = 48_000

    private fun sine(freq: Double, amp: Float, seconds: Double) =
        FloatArray((sr * seconds).toInt()) { (amp * sin(2 * PI * freq * it / sr)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun db(x: Double) = 20 * log10(x)

    @Test
    fun fftRoundTrip() {
        val fft = Fft(256)
        val rnd = Random(1)
        val re = FloatArray(256) { rnd.nextFloat() - 0.5f }
        val orig = re.copyOf()
        val im = FloatArray(256)
        fft.forward(re, im)
        fft.inverse(re, im)
        assertArrayEquals(orig, re, 1e-5f)
    }

    @Test
    fun fftFindsSinePeak() {
        val n = 1024
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2 * PI * 64 * it / n).toFloat() }
        val im = FloatArray(n)
        fft.forward(re, im)
        val mags = (0..n / 2).map { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, mags.indices.maxBy { mags[it] })
    }

    @Test
    fun denoiserPreservesToneLevel() {
        // A loud tone after a silent learning period must survive denoising at near full level.
        val d = SpectralDenoiser(sr).apply { strength = 0.6f }
        val rnd = Random(7)
        val noiseAmp = 0.003f
        val learn = FloatArray(sr) { (rnd.nextGaussian() * noiseAmp).toFloat() }
        d.process(learn, 0, learn.size)
        val tone = sine(440.0, 0.3f, 2.0)
        for (i in tone.indices) tone[i] += (rnd.nextGaussian() * noiseAmp).toFloat()
        val inRms = rms(tone)
        d.process(tone, 0, tone.size)
        val outRms = rms(tone, sr / 2, tone.size)
        assertTrue("tone lost ${db(outRms / inRms)} dB", abs(db(outRms / inRms)) < 1.0)
    }

    @Test
    fun denoiserRemovesStationaryNoise() {
        val d = SpectralDenoiser(sr).apply { strength = 0.7f }
        val rnd = Random(3)
        val noise = FloatArray(sr * 3) { (rnd.nextGaussian() * 0.02).toFloat() }
        val inRms = rms(noise, sr, noise.size)
        d.process(noise, 0, noise.size)
        val outRms = rms(noise, sr, noise.size)
        val reduction = db(inRms / outRms)
        assertTrue("only ${"%.1f".format(reduction)} dB of noise removed", reduction > 12)
    }

    @Test
    fun highPassRemovesRumbleKeepsVoice() {
        val hp = HighPassFilter(sr, 100f)
        val rumble = sine(30.0, 0.5f, 1.0)
        hp.process(rumble, 0, rumble.size)
        assertTrue(db(rms(rumble, sr / 2, rumble.size) / (0.5 / sqrt(2.0))) < -30)

        hp.reset()
        val voice = sine(1000.0, 0.5f, 1.0)
        hp.process(voice, 0, voice.size)
        assertEquals(0.0, db(rms(voice, sr / 2, voice.size) / (0.5 / sqrt(2.0))), 0.2)
    }

    @Test
    fun limiterNeverExceedsCeiling() {
        val lim = Limiter(sr).apply { ceilingDb = -1f }
        val loud = sine(220.0, 1.8f, 1.0)
        lim.process(loud, 0, loud.size)
        val ceiling = dbToLinear(-1f)
        assertTrue(loud.all { abs(it) <= ceiling + 1e-6f })
        assertTrue(loud.drop(sr / 2).maxOf { abs(it) } > ceiling * 0.95f)
    }

    @Test
    fun compressorReducesLoudSignal() {
        val c = Compressor(sr).apply { thresholdDb = -20f; ratio = 4f; makeupDb = 0f }
        val x = sine(500.0, 0.9f, 1.0)
        c.process(x, 0, x.size)
        val outDb = db(rms(x, sr / 2, x.size))
        // Input RMS ≈ -3.9 dBFS; peaks well over threshold, so expect substantial reduction.
        assertTrue("out $outDb", outDb < -10)
        assertTrue(c.gainReductionDb < -6)
    }

    @Test
    fun loudnessOfReferenceTone() {
        // BS.1770: a 997 Hz sine at amplitude A reads -3.01 + 20log10(A) LUFS.
        val meter = LoudnessMeter(sr, 1)
        val x = sine(997.0, 0.1f, 5.0)
        meter.add(x, x.size)
        assertEquals(-23.01f, meter.integratedLufs()!!, 0.1f)
    }

    @Test
    fun wavRoundTripAndNormalization() {
        val dir = createTempDir()
        val file = File(dir, "t.wav")
        val x = sine(997.0, 0.05f, 4.0)
        WavWriter(file, sr, 1).use { it.write(x, 0, x.size) }
        WavReader(file).use { r ->
            assertEquals(sr, r.sampleRate)
            assertEquals(1, r.channels)
            assertEquals(x.size.toLong(), r.totalFrames)
        }

        OfflineProcessor.normalize(file, targetLufs = -16f, ceilingDb = -1f)
        val meter = LoudnessMeter(sr, 1)
        WavReader(file).use { r ->
            assertEquals(x.size.toLong(), r.totalFrames)
            val buf = FloatArray(x.size)
            val n = r.read(buf, buf.size)
            meter.add(buf, n)
        }
        assertEquals(-16f, meter.integratedLufs()!!, 0.3f)
        dir.deleteRecursively()
    }

    @Test
    fun enhanceKeepsLengthAndAlignment() {
        val dir = createTempDir()
        val input = File(dir, "in.wav")
        val output = File(dir, "out.wav")
        val rnd = Random(5)
        val x = FloatArray(sr * 3) { i ->
            val noise = (rnd.nextGaussian() * 0.005).toFloat()
            if (i > sr) noise + (0.3 * sin(2 * PI * 300 * i / sr)).toFloat() else noise
        }
        WavWriter(input, sr, 1).use { it.write(x, 0, x.size) }
        OfflineProcessor.enhance(input, output, Preset.PODCAST.settings)
        WavReader(output).use { r ->
            assertEquals(x.size.toLong(), r.totalFrames)
            val y = FloatArray(x.size)
            r.read(y, y.size)
            // Noise-only first second should be much quieter than the tone section.
            assertTrue(db(rms(y, sr / 2, sr - 2000) / rms(y, sr + 4000, 2 * sr)) < -30)
            assertNotNull(y)
        }
        dir.deleteRecursively()
    }

    @Test
    fun stereoChainProcessesChannelsIndependently() {
        val chain = ProcessingChain(sr, 2, Preset.RAW.settings.copy(limiterEnabled = true))
        val frames = 4800
        val buf = FloatArray(frames * 2) { if (it % 2 == 0) 0.5f else 0f }
        chain.process(buf, frames)
        assertTrue((0 until frames).all { buf[it * 2 + 1] == 0f })
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): File = kotlin.io.createTempDir("vocalis")
}
