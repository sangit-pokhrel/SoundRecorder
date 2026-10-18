package com.vocalis.recorder.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Streaming spectral noise reduction.
 *
 * The signal is analysed with a 75%-overlapped STFT (sqrt-Hann analysis and synthesis windows).
 * Per frequency bin a noise floor is tracked continuously (minimum tracking with a slow rise, so
 * it follows changing background noise like fans, traffic or hiss) and a decision-directed
 * Wiener gain (Ephraim & Malah) suppresses bins dominated by noise. The decision-directed
 * a-priori SNR plus a little cross-frequency smoothing keeps "musical noise" artefacts low.
 *
 * The first [LEARN_SECONDS] of audio (or the next ones after [relearn]) are averaged to seed the
 * noise profile, so starting a recording with a moment of silence gives the best result.
 */
class SpectralDenoiser(
    private val sampleRate: Int,
    private val fftSize: Int = if (sampleRate > 32_000) 1024 else 512,
) : AudioProcessor {

    /** 0 = gentle, 1 = aggressive. Safe to change from any thread. */
    @Volatile var strength: Float = 0.6f

    /** Recent amount of energy removed, in dB. Updated per frame for metering. */
    @Volatile var reductionDb: Float = 0f
        private set

    @Volatile private var relearnRequested = false

    private val hop = fftSize / 4
    private val bins = fftSize / 2 + 1
    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { sqrt(0.5f - 0.5f * cos(2f * PI.toFloat() * it / fftSize)) }

    // With sqrt-Hann on both analysis and synthesis, the overlapped windows sum to 2 at hop N/4.
    private val olaScale = 0.5f

    private val inFifo = FloatArray(fftSize)
    private val outAccum = FloatArray(fftSize)
    private val outFifo = FloatArray(hop)
    private var hopPos = 0

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val noise = FloatArray(bins)
    private val smoothPower = FloatArray(bins)
    private val prevGain = FloatArray(bins) { 1f }
    private val prevPost = FloatArray(bins) { 1f }
    private val gain = FloatArray(bins)

    private val learnFrames = max(1, (LEARN_SECONDS * sampleRate / hop).toInt())
    private var learnedFrames = 0

    private val noiseRise = 10f.pow(NOISE_RISE_DB_PER_SEC / 10f * hop / sampleRate)

    private var energyIn = 0f
    private var energyOut = 0f

    override val latencySamples: Int get() = fftSize

    /** Re-seed the noise profile from the next [LEARN_SECONDS] of input. */
    fun relearn() {
        relearnRequested = true
    }

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val x = buffer[i]
            buffer[i] = outFifo[hopPos]
            inFifo[fftSize - hop + hopPos] = x
            if (++hopPos == hop) {
                hopPos = 0
                processFrame()
            }
        }
    }

    override fun reset() {
        inFifo.fill(0f)
        outAccum.fill(0f)
        outFifo.fill(0f)
        noise.fill(0f)
        smoothPower.fill(0f)
        prevGain.fill(1f)
        prevPost.fill(1f)
        hopPos = 0
        learnedFrames = 0
    }

    private fun processFrame() {
        if (relearnRequested) {
            relearnRequested = false
            learnedFrames = 0
            noise.fill(0f)
        }

        for (i in 0 until fftSize) {
            re[i] = inFifo[i] * window[i]
            im[i] = 0f
        }
        fft.forward(re, im)

        val s = strength.coerceIn(0f, 1f)
        val overSubtract = 1f + 1.5f * s
        val floor = dbToLinear(-(6f + 24f * s))
        val learning = learnedFrames < learnFrames

        var frameIn = 0f
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            frameIn += p

            if (learning) {
                noise[k] += p / learnFrames
                smoothPower[k] = p
            } else {
                smoothPower[k] = 0.7f * smoothPower[k] + 0.3f * p
                noise[k] = if (smoothPower[k] < noise[k]) {
                    0.7f * noise[k] + 0.3f * smoothPower[k]
                } else {
                    noise[k] * noiseRise
                }
            }

            val n = overSubtract * noise[k] + 1e-12f
            val post = p / n
            val prio = DD_ALPHA * prevGain[k] * prevGain[k] * prevPost[k] +
                (1f - DD_ALPHA) * max(post - 1f, 0f)
            val g = if (learning) 1f else max(prio / (1f + prio), floor)
            gain[k] = g
            prevGain[k] = g
            prevPost[k] = post
        }
        if (learning) learnedFrames++

        // Light smoothing across frequency, then apply to both halves of the Hermitian spectrum.
        var frameOut = 0f
        var prev = gain[0]
        for (k in 0 until bins) {
            val cur = gain[k]
            val next = if (k + 1 < bins) gain[k + 1] else cur
            val g = 0.25f * prev + 0.5f * cur + 0.25f * next
            prev = cur
            re[k] *= g
            im[k] *= g
            frameOut += re[k] * re[k] + im[k] * im[k]
            if (k in 1 until fftSize / 2) {
                re[fftSize - k] *= g
                im[fftSize - k] *= g
            }
        }

        energyIn = 0.9f * energyIn + 0.1f * frameIn
        energyOut = 0.9f * energyOut + 0.1f * frameOut
        reductionDb = 10f * log10((energyIn + 1e-9f) / (energyOut + 1e-9f))

        fft.inverse(re, im)

        for (i in 0 until fftSize) outAccum[i] += re[i] * window[i] * olaScale
        System.arraycopy(outAccum, 0, outFifo, 0, hop)
        System.arraycopy(outAccum, hop, outAccum, 0, fftSize - hop)
        outAccum.fill(0f, fftSize - hop, fftSize)
        System.arraycopy(inFifo, hop, inFifo, 0, fftSize - hop)
    }

    companion object {
        const val LEARN_SECONDS = 0.4f
        private const val NOISE_RISE_DB_PER_SEC = 2f
        private const val DD_ALPHA = 0.98f
    }
}
