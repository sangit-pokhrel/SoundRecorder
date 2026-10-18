package com.vocalis.recorder.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Detects breaths and turns them down (not out: fully silent gaps sound unnatural).
 *
 * A breath is recognised by four cues measured on every ~5 ms hop:
 *  - **no pitch**: low normalized autocorrelation in the voice pitch range (vowels are periodic);
 *  - **noise-like**: high spectral flatness between 300 Hz and 6 kHz;
 *  - **not a sibilant**: most energy below 4.5 kHz (an "s" lives above that);
 *  - **quieter than speech**: well under the tracked speech level, but above the noise floor.
 *
 * Unvoiced consonants like "h" or "f" match some cues but are short, so a hop is only treated as
 * breath when it belongs to a run of at least [MIN_BREATH_SEC]. A look-ahead delay lets the gain
 * start dropping right at the breath onset instead of after the run has been confirmed.
 */
class BreathReducer(private val sampleRate: Int) : AudioProcessor {

    /** How much detected breaths are turned down. Safe to change from any thread. */
    @Volatile var reductionDb = 12f

    /** Number of breaths softened so far, for metering. */
    @Volatile var breathsDetected = 0
        private set

    private val frameSize = if (sampleRate > 32_000) 1024 else 512
    private val hop = frameSize / 4
    private val fftSize = frameSize * 2 // zero-padded so the autocorrelation isn't circular
    private val fft = Fft(fftSize)
    private val window = FloatArray(frameSize) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / frameSize) }
    private val windowAutocorr: FloatArray
    private val history = FloatArray(frameSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val power = FloatArray(fftSize / 2 + 1)
    private var hopFill = 0

    private val minRunHops = ceil(MIN_BREATH_SEC * sampleRate / hop).toInt()
    private val maxRunHops = (MAX_BREATH_SEC * sampleRate / hop).toInt()
    private val lookaheadHops = minRunHops + 4
    private val latency = lookaheadHops * hop
    private val delay = FloatArray(latency)
    private var delayPos = 0
    private var samplesIn = 0L
    private var hopsDone = 0L

    private val flags = BooleanArray(lookaheadHops + 16)
    private var runStart = -1L
    private var lastCandidate = -1L
    private var runCounted = false

    private var speechDb = -20f
    private var floorDb = Float.NaN
    private var gain = 1f
    private val fadeCoef = 1f - timeCoefficient(15f, sampleRate)

    // Bin ranges for the spectral features.
    private fun bin(hz: Float) = (hz * fftSize / sampleRate).toInt().coerceIn(1, fftSize / 2)
    private val bin300 = bin(300f)
    private val bin1500 = bin(1500f)
    private val bin4500 = bin(4500f)
    private val bin6000 = bin(6000f)
    private val binTop = bin(min(10_000f, sampleRate / 2f - 1))
    private val minLag = sampleRate / 400
    private val maxLag = min(sampleRate / 80, frameSize / 2)

    override val latencySamples: Int get() = latency

    init {
        // Autocorrelation of the window itself, used to undo the Hann taper's bias toward short lags.
        var energy = 0f
        for (w in window) energy += w * w
        windowAutocorr = FloatArray(maxLag + 1) { lag ->
            var s = 0f
            for (i in 0 until frameSize - lag) s += window[i] * window[i + lag]
            s / energy
        }
    }

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        val reduced = dbToLinear(-reductionDb)
        for (i in offset until offset + length) {
            val x = buffer[i]
            history[frameSize - hop + hopFill] = x

            // Output: the sample that entered `latency` samples ago, at that hop's gain.
            val outIndex = samplesIn - latency
            val target = if (outIndex >= 0 && flags[((outIndex / hop) % flags.size).toInt()]) reduced else 1f
            gain += (target - gain) * fadeCoef
            val delayed = delay[delayPos]
            delay[delayPos] = x
            delayPos = if (delayPos + 1 == latency) 0 else delayPos + 1
            buffer[i] = delayed * gain
            samplesIn++

            if (++hopFill == hop) {
                hopFill = 0
                analyse()
                System.arraycopy(history, hop, history, 0, frameSize - hop)
            }
        }
    }

    override fun reset() {
        history.fill(0f)
        delay.fill(0f)
        flags.fill(false)
        delayPos = 0
        hopFill = 0
        samplesIn = 0
        hopsDone = 0
        runStart = -1
        lastCandidate = -1
        runCounted = false
        gain = 1f
        speechDb = -20f
        floorDb = Float.NaN
    }

    private fun analyse() {
        // The frame ends at the current hop; attribute the decision to the hop at its centre.
        val h = hopsDone++
        val c = h - (frameSize / hop) / 2 + 1
        if (c < 0) return
        flags[(c % flags.size).toInt()] = false

        var sumSq = 0f
        for (i in 0 until frameSize) {
            re[i] = history[i] * window[i]
            sumSq += history[i] * history[i]
        }
        re.fill(0f, frameSize, fftSize)
        im.fill(0f)
        val levelDb = 10f * log10(sumSq / frameSize + 1e-12f)
        fft.forward(re, im)
        for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]

        // Spectral flatness (geometric / arithmetic mean) in the 300 Hz – 6 kHz band.
        var logSum = 0.0
        var linSum = 0.0
        for (k in bin300..bin6000) {
            logSum += ln(power[k] + 1e-20)
            linSum += power[k]
        }
        val count = bin6000 - bin300 + 1
        val flatness = (exp(logSum / count) / (linSum / count + 1e-20)).toFloat()

        // Share of energy that sits up where sibilants live.
        var high = 0f
        var total = 0f
        for (k in bin300..binTop) {
            total += power[k]
            if (k >= bin4500) high += power[k]
        }
        val highRatio = if (total > 0f) high / total else 0f

        // Periodicity: autocorrelation (inverse FFT of the low-passed power spectrum).
        for (k in 0 until fftSize) {
            val mirrored = if (k <= fftSize / 2) k else fftSize - k
            re[k] = if (mirrored <= bin1500) power[mirrored] else 0f
            im[k] = 0f
        }
        fft.inverse(re, im)
        var periodicity = 0f
        if (re[0] > 1e-12f) {
            for (lag in minLag..maxLag) {
                val r = re[lag] / re[0] / max(windowAutocorr[lag], 0.1f)
                if (r > periodicity) periodicity = r
            }
        }

        // Track the noise floor (fast down, slow up) and the level of voiced speech.
        floorDb = if (floorDb.isNaN() || levelDb < floorDb) levelDb else floorDb + FLOOR_RISE_DB_PER_HOP
        val voiced = periodicity > VOICED_PERIODICITY && levelDb > floorDb + 10f
        // Peak-style follower: quick to rise, slow to fall, so vowel tails don't drag it down.
        if (voiced) speechDb += (if (levelDb > speechDb) 0.3f else 0.004f) * (levelDb - speechDb)

        val candidate = periodicity < BREATH_MAX_PERIODICITY &&
            flatness > BREATH_MIN_FLATNESS &&
            highRatio < BREATH_MAX_HIGH_RATIO &&
            levelDb < speechDb - BREATH_BELOW_SPEECH_DB &&
            levelDb > floorDb + 4f &&
            levelDb > -75f

        if (candidate) {
            if (runStart < 0 || c - lastCandidate > MAX_GAP_HOPS + 1) {
                runStart = c
                runCounted = false
            }
            lastCandidate = c
            val span = c - runStart + 1
            if (span in minRunHops..maxRunHops) {
                if (!runCounted) {
                    runCounted = true
                    breathsDetected++
                }
                // Mark the whole run (including short gaps) once it's long enough to be a breath.
                val from = max(runStart, c - flags.size + 1)
                for (j in from..c) flags[(j % flags.size).toInt()] = true
            } else if (span > maxRunHops) {
                // Too long for a breath: sustained noise or music. Stop ducking it.
                flags[(c % flags.size).toInt()] = false
            }
        } else if (runStart >= 0 && c - lastCandidate > MAX_GAP_HOPS) {
            runStart = -1
        }
    }

    companion object {
        const val MIN_BREATH_SEC = 0.12f
        const val MAX_BREATH_SEC = 1.5f
        private const val MAX_GAP_HOPS = 4
        private const val VOICED_PERIODICITY = 0.85f
        private const val BREATH_MAX_PERIODICITY = 0.8f
        private const val BREATH_MIN_FLATNESS = 0.15f
        private const val BREATH_MAX_HIGH_RATIO = 0.55f
        private const val BREATH_BELOW_SPEECH_DB = 6f
        private const val FLOOR_RISE_DB_PER_HOP = 0.04f
    }
}
