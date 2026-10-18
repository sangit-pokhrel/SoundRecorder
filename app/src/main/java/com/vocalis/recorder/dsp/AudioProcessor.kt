package com.vocalis.recorder.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * A mono, block-based audio processor operating in place on float samples in [-1, 1].
 *
 * Processors are stateful and not thread-safe: create one per channel and drive it from a
 * single audio thread.
 */
interface AudioProcessor {
    /** Delay, in samples, between a sample entering [process] and its processed output. */
    val latencySamples: Int get() = 0

    fun process(buffer: FloatArray, offset: Int, length: Int)

    fun reset() {}
}

fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

fun linearToDb(x: Float): Float = if (x <= 1e-9f) -180f else 20f * log10(x)

/** One-pole smoothing coefficient that decays to 1/e in [ms] milliseconds. */
fun timeCoefficient(ms: Float, sampleRate: Int): Float =
    if (ms <= 0f) 0f else exp(-1f / (ms * 0.001f * sampleRate))
