package com.vocalis.recorder.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic take: voiced speech, a breath, more speech, an "s", a short "h", more speech.
 * The breath must be turned down; speech, the sibilant and the short consonant must not.
 */
class BreathReducerTest {
    private val sr = 48_000
    private val rnd = Random(11)

    private fun sec(s: Double) = (s * sr).toInt()

    /** Harmonic "vowel" with a gently moving pitch, normalized to [rmsDb]. */
    private fun voice(seconds: Double, rmsDb: Double): FloatArray {
        val n = sec(seconds)
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val f0 = 140 + 15 * sin(2 * PI * 3 * i / sr)
            phase += 2 * PI * f0 / sr
            var v = 0.0
            for (h in 1..25) if (h * f0 < 4500) v += sin(h * phase) / h
            out[i] = v.toFloat()
        }
        return normalize(fade(out), rmsDb)
    }

    /** Band-limited noise between [lowHz] and [highHz] with a smooth envelope. */
    private fun noise(seconds: Double, lowHz: Float, highHz: Float, rmsDb: Double): FloatArray {
        val hp1 = Biquad().apply { setHighPass(sr, lowHz) }
        val hp2 = Biquad().apply { setHighPass(sr, lowHz) }
        val lp1 = Biquad().apply { setLowPass(sr, highHz) }
        val lp2 = Biquad().apply { setLowPass(sr, highHz) }
        val out = FloatArray(sec(seconds)) {
            lp2.process(lp1.process(hp2.process(hp1.process(rnd.nextGaussian().toFloat()))))
        }
        return normalize(fade(out), rmsDb)
    }

    private fun fade(x: FloatArray): FloatArray {
        val n = x.size
        for (i in 0 until n) x[i] *= (0.5 - 0.5 * cos(2 * PI * i / n)).toFloat().let { sqrt(it) }
        return x
    }

    private fun normalize(x: FloatArray, rmsDb: Double): FloatArray {
        val g = (Math.pow(10.0, rmsDb / 20) / rms(x, 0, x.size)).toFloat()
        for (i in x.indices) x[i] *= g
        return x
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun silence(seconds: Double) = FloatArray(sec(seconds))

    private data class Segment(val start: Int, val end: Int)

    @Test
    fun softensBreathsButNotSpeechSibilantsOrShortConsonants() = runScenario(breathDb = -36.0, roomNoise = 0.0003)

    @Test
    fun handlesLoudBreathInNoisyRoom() = runScenario(breathDb = -30.0, roomNoise = 0.003)

    private fun runScenario(breathDb: Double, roomNoise: Double) {
        val parts = listOf(
            "speech1" to voice(1.5, -18.0),
            "gap1" to silence(0.1),
            "breath" to noise(0.45, 400f, 3500f, breathDb),
            "gap2" to silence(0.1),
            "speech2" to voice(1.5, -18.0),
            "s" to noise(0.15, 5000f, 11000f, -28.0),
            "speech3" to voice(1.0, -18.0),
            "h" to noise(0.06, 400f, 3500f, -34.0),
            "speech4" to voice(1.5, -18.0),
            "tail" to silence(0.5),
        )
        val segments = mutableMapOf<String, Segment>()
        val total = parts.sumOf { it.second.size }
        val input = FloatArray(total)
        var pos = 0
        for ((name, data) in parts) {
            System.arraycopy(data, 0, input, pos, data.size)
            segments[name] = Segment(pos, pos + data.size)
            pos += data.size
        }
        // Quiet room tone everywhere.
        for (i in input.indices) input[i] += (rnd.nextGaussian() * roomNoise).toFloat()

        val reducer = BreathReducer(sr).apply { reductionDb = 12f }
        val latency = reducer.latencySamples
        val output = input.copyOf(total + latency)
        reducer.process(output, 0, output.size)
        val aligned = output.copyOfRange(latency, latency + total)

        fun changeDb(name: String, trim: Double = 0.2): Double {
            val seg = segments.getValue(name)
            val pad = ((seg.end - seg.start) * trim).toInt()
            val a = rms(input, seg.start + pad, seg.end - pad)
            val b = rms(aligned, seg.start + pad, seg.end - pad)
            return 20 * log10(b / a)
        }

        val breath = changeDb("breath")
        assertTrue("breath only reduced by %.1f dB".format(-breath), breath < -8)
        for (s in listOf("speech1", "speech2", "speech3", "speech4")) {
            assertEquals("$s changed", 0.0, changeDb(s), 0.5)
        }
        assertEquals("sibilant changed", 0.0, changeDb("s"), 1.0)
        assertEquals("short consonant changed", 0.0, changeDb("h"), 1.5)
        assertEquals(1, reducer.breathsDetected)
    }
}
