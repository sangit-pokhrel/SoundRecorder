package com.vocalis.recorder.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness (LUFS) per ITU-R BS.1770-4 / EBU R128: K-weighting, 400 ms blocks with
 * 75% overlap, absolute gate at -70 LUFS and relative gate at -10 LU.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private val stage1 = Array(channels) { Biquad() }
    private val stage2 = Array(channels) { Biquad() }
    private val subBlockFrames = sampleRate / 10
    private var subBlockSum = 0.0
    private var subBlockCount = 0
    private var subBlocks = DoubleArray(1024)
    private var subBlockTotal = 0

    init {
        // Stage 1: high-shelf modelling the acoustic effect of the head.
        run {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(g / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            stage1.forEach {
                it.setCoefficients(
                    vh + vb * k / q + k * k, 2 * (k * k - vh), vh - vb * k / q + k * k,
                    a0, 2 * (k * k - 1), 1 - k / q + k * k,
                )
            }
        }
        // Stage 2: RLB high-pass.
        run {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            stage2.forEach {
                it.setCoefficients(1.0, -2.0, 1.0, 1 + k / q + k * k, 2 * (k * k - 1), 1 - k / q + k * k)
            }
        }
    }

    /** Feed interleaved samples. */
    fun add(interleaved: FloatArray, frames: Int) {
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val y = stage2[c].process(stage1[c].process(interleaved[idx++]))
                sum += y.toDouble() * y
            }
            subBlockSum += sum
            if (++subBlockCount == subBlockFrames) {
                if (subBlockTotal == subBlocks.size) subBlocks = subBlocks.copyOf(subBlocks.size * 2)
                subBlocks[subBlockTotal++] = subBlockSum / subBlockFrames
                subBlockSum = 0.0
                subBlockCount = 0
            }
        }
    }

    /** Integrated loudness in LUFS, or null if there is not enough (non-silent) audio. */
    fun integratedLufs(): Float? {
        val blockCount = subBlockTotal - 3
        if (blockCount <= 0) return null
        val blocks = DoubleArray(blockCount) {
            (subBlocks[it] + subBlocks[it + 1] + subBlocks[it + 2] + subBlocks[it + 3]) / 4.0
        }
        fun loudness(ms: Double) = -0.691 + 10 * log10(ms)
        val absGated = blocks.filter { it > 0 && loudness(it) > -70.0 }
        if (absGated.isEmpty()) return null
        val relThreshold = loudness(absGated.average()) - 10.0
        val relGated = absGated.filter { loudness(it) > relThreshold }
        if (relGated.isEmpty()) return null
        return loudness(relGated.average()).toFloat()
    }
}
