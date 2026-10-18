package com.vocalis.recorder.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Removes rumble, handling noise and wind boom below [cutoffHz] (24 dB/octave). */
class HighPassFilter(private val sampleRate: Int, cutoffHz: Float) : AudioProcessor {
    private val stage1 = Biquad()
    private val stage2 = Biquad()

    var cutoffHz: Float = cutoffHz
        set(value) {
            field = value
            // Butterworth 4th order = two biquads with these Qs.
            stage1.setHighPass(sampleRate, value, 0.5412f)
            stage2.setHighPass(sampleRate, value, 1.3066f)
        }

    init {
        this.cutoffHz = cutoffHz
    }

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        for (i in offset until offset + length) buffer[i] = stage2.process(stage1.process(buffer[i]))
    }

    override fun reset() {
        stage1.reset()
        stage2.reset()
    }
}

/**
 * Downward expander / gate: attenuates the signal by [rangeDb] when it falls below
 * [thresholdDb], with hysteresis and hold so word endings aren't chopped.
 */
class NoiseGate(private val sampleRate: Int) : AudioProcessor {
    var thresholdDb = -50f
    var rangeDb = -30f
    var holdMs = 80f

    private val envRelease = timeCoefficient(15f, sampleRate)
    private val openCoef = 1f - timeCoefficient(1.5f, sampleRate)
    private val closeCoef = 1f - timeCoefficient(120f, sampleRate)
    private var env = 0f
    private var gain = 1f
    private var open = false
    private var holdLeft = 0

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        val openThreshold = dbToLinear(thresholdDb)
        val closeThreshold = dbToLinear(thresholdDb - HYSTERESIS_DB)
        val floor = dbToLinear(rangeDb)
        val holdSamples = (holdMs * 0.001f * sampleRate).toInt()
        for (i in offset until offset + length) {
            val a = abs(buffer[i])
            env = if (a > env) a else env * envRelease
            if (env > openThreshold) {
                open = true
                holdLeft = holdSamples
            } else if (env < closeThreshold) {
                if (holdLeft > 0) holdLeft-- else open = false
            }
            val target = if (open) 1f else floor
            gain += (target - gain) * (if (target > gain) openCoef else closeCoef)
            buffer[i] *= gain
        }
    }

    override fun reset() {
        env = 0f
        gain = 1f
        open = false
        holdLeft = 0
    }

    private companion object {
        const val HYSTERESIS_DB = 4f
    }
}

/** Feed-forward soft-knee compressor that evens out loud and quiet passages. */
class Compressor(private val sampleRate: Int) : AudioProcessor {
    var thresholdDb = -20f
    var ratio = 3f
    var kneeDb = 6f
    var makeupDb = 0f
    var attackMs = 8f
        set(value) { field = value; attack = timeCoefficient(value, sampleRate) }
    var releaseMs = 120f
        set(value) { field = value; release = timeCoefficient(value, sampleRate) }

    /** Current gain reduction in dB (<= 0), for metering. */
    @Volatile var gainReductionDb = 0f
        private set

    private var attack = timeCoefficient(attackMs, sampleRate)
    private var release = timeCoefficient(releaseMs, sampleRate)
    private var grSmooth = 0f

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        val slope = 1f / ratio - 1f
        val knee = max(kneeDb, 0.001f)
        for (i in offset until offset + length) {
            val level = linearToDb(abs(buffer[i]))
            val over = level - thresholdDb
            val gr = when {
                2f * over < -knee -> 0f
                2f * abs(over) <= knee -> slope * (over + knee / 2f) * (over + knee / 2f) / (2f * knee)
                else -> slope * over
            }
            grSmooth = if (gr < grSmooth) {
                attack * grSmooth + (1f - attack) * gr
            } else {
                release * grSmooth + (1f - release) * gr
            }
            buffer[i] *= dbToLinear(grSmooth + makeupDb)
        }
        gainReductionDb = grSmooth
    }

    override fun reset() {
        grSmooth = 0f
    }
}

/**
 * Split-band de-esser: tames harsh "s"/"sh" sounds by turning down only the high band when its
 * level exceeds [thresholdDb]. Output = x - high * (1 - g), which is transparent when g = 1.
 */
class DeEsser(private val sampleRate: Int) : AudioProcessor {
    var thresholdDb = -30f
    var frequencyHz = 5500f
        set(value) { field = value; split.setHighPass(sampleRate, value, 0.7071f) }

    private val split = Biquad().apply { setHighPass(sampleRate, frequencyHz, 0.7071f) }
    private val envCoef = timeCoefficient(5f, sampleRate)
    private val attack = timeCoefficient(1f, sampleRate)
    private val release = timeCoefficient(60f, sampleRate)
    private var env = 0f
    private var grSmooth = 0f

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val x = buffer[i]
            val high = split.process(x)
            env = envCoef * env + (1f - envCoef) * high * high
            val levelDb = 10f * log10(env + 1e-12f)
            val over = levelDb - thresholdDb
            val gr = if (over > 0f) -over * (1f - 1f / RATIO) else 0f
            grSmooth = if (gr < grSmooth) attack * grSmooth + (1f - attack) * gr
            else release * grSmooth + (1f - release) * gr
            buffer[i] = x - high * (1f - dbToLinear(grSmooth))
        }
    }

    override fun reset() {
        split.reset()
        env = 0f
        grSmooth = 0f
    }

    private companion object {
        const val RATIO = 4f
    }
}

/** Voice-oriented 4-band EQ: warmth, mud cut, presence and air. */
class VoiceEqualizer(private val sampleRate: Int) : AudioProcessor {
    private val low = Biquad()
    private val mud = Biquad()
    private val presence = Biquad()
    private val air = Biquad()
    private val active = BooleanArray(4)

    fun configure(lowDb: Float, mudDb: Float, presenceDb: Float, airDb: Float) {
        low.setLowShelf(sampleRate, 120f, lowDb)
        mud.setPeaking(sampleRate, 350f, 1.0f, mudDb)
        presence.setPeaking(sampleRate, 3200f, 0.9f, presenceDb)
        air.setHighShelf(sampleRate, 10_000f, airDb)
        active[0] = abs(lowDb) > 0.05f
        active[1] = abs(mudDb) > 0.05f
        active[2] = abs(presenceDb) > 0.05f
        active[3] = abs(airDb) > 0.05f
    }

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            var x = buffer[i]
            if (active[0]) x = low.process(x)
            if (active[1]) x = mud.process(x)
            if (active[2]) x = presence.process(x)
            if (active[3]) x = air.process(x)
            buffer[i] = x
        }
    }

    override fun reset() {
        low.reset(); mud.reset(); presence.reset(); air.reset()
    }
}

/**
 * Look-ahead peak limiter that keeps peaks under [ceilingDb] so recordings never clip.
 * A sliding-window minimum (monotonic deque) finds the gain needed for the next
 * [lookaheadMs] of audio before it reaches the output.
 */
class Limiter(private val sampleRate: Int, lookaheadMs: Float = 3f) : AudioProcessor {
    var ceilingDb = -1f

    private val lookahead = max(1, (lookaheadMs * 0.001f * sampleRate).toInt())
    private val delay = FloatArray(lookahead)
    private var delayPos = 0

    private val dqValue = FloatArray(lookahead + 2)
    private val dqIndex = LongArray(lookahead + 2)
    private var dqHead = 0
    private var dqSize = 0
    private var counter = 0L

    private val attack = kotlin.math.exp(-4.6f / lookahead)
    private val release = timeCoefficient(80f, sampleRate)
    private var gain = 1f

    override val latencySamples: Int get() = lookahead

    override fun process(buffer: FloatArray, offset: Int, length: Int) {
        val ceiling = dbToLinear(ceilingDb)
        val cap = dqValue.size
        for (i in offset until offset + length) {
            val x = buffer[i]
            val a = abs(x)
            val target = if (a > ceiling) ceiling / a else 1f

            // Push target, keeping the deque increasing so the head is the window minimum.
            while (dqSize > 0 && dqValue[(dqHead + dqSize - 1) % cap] >= target) dqSize--
            val tail = (dqHead + dqSize) % cap
            dqValue[tail] = target
            dqIndex[tail] = counter
            dqSize++
            while (dqIndex[dqHead] <= counter - lookahead - 1) {
                dqHead = (dqHead + 1) % cap
                dqSize--
            }
            val windowMin = dqValue[dqHead]
            counter++

            gain = if (windowMin < gain) windowMin + (gain - windowMin) * attack
            else 1f - (1f - gain) * release
            gain = min(gain, 1f)

            val delayed = delay[delayPos]
            delay[delayPos] = x
            delayPos = (delayPos + 1) % lookahead

            buffer[i] = (delayed * gain).coerceIn(-ceiling, ceiling)
        }
    }

    override fun reset() {
        delay.fill(0f)
        delayPos = 0
        dqHead = 0
        dqSize = 0
        counter = 0
        gain = 1f
    }
}
