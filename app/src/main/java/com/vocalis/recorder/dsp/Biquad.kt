package com.vocalis.recorder.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR filter (transposed direct form II) with coefficient helpers from
 * Robert Bristow-Johnson's "Audio EQ Cookbook".
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun setCoefficients(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        this.b0 = b0 / a0
        this.b1 = b1 / a0
        this.b2 = b2 / a0
        this.a1 = a1 / a0
        this.a2 = a2 / a0
    }

    fun setHighPass(sampleRate: Int, freq: Float, q: Float = 0.7071f) {
        val (cw, alpha) = prewarp(sampleRate, freq, q)
        setCoefficients((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    fun setLowPass(sampleRate: Int, freq: Float, q: Float = 0.7071f) {
        val (cw, alpha) = prewarp(sampleRate, freq, q)
        setCoefficients((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    fun setPeaking(sampleRate: Int, freq: Float, q: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val (cw, alpha) = prewarp(sampleRate, freq, q)
        setCoefficients(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
    }

    fun setLowShelf(sampleRate: Int, freq: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val (cw, alpha) = prewarp(sampleRate, freq, SHELF_Q)
        val s = 2 * sqrt(a) * alpha
        setCoefficients(
            a * ((a + 1) - (a - 1) * cw + s),
            2 * a * ((a - 1) - (a + 1) * cw),
            a * ((a + 1) - (a - 1) * cw - s),
            (a + 1) + (a - 1) * cw + s,
            -2 * ((a - 1) + (a + 1) * cw),
            (a + 1) + (a - 1) * cw - s,
        )
    }

    fun setHighShelf(sampleRate: Int, freq: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val (cw, alpha) = prewarp(sampleRate, freq, SHELF_Q)
        val s = 2 * sqrt(a) * alpha
        setCoefficients(
            a * ((a + 1) + (a - 1) * cw + s),
            -2 * a * ((a - 1) + (a + 1) * cw),
            a * ((a + 1) + (a - 1) * cw - s),
            (a + 1) - (a - 1) * cw + s,
            2 * ((a - 1) - (a + 1) * cw),
            (a + 1) - (a - 1) * cw - s,
        )
    }

    private fun prewarp(sampleRate: Int, freq: Float, q: Float): Pair<Double, Double> {
        val f = freq.toDouble().coerceIn(10.0, sampleRate * 0.45)
        val w0 = 2 * PI * f / sampleRate
        return cos(w0) to sin(w0) / (2 * q)
    }

    private companion object {
        /** Q giving a shelf slope of S = 1 (no overshoot). */
        const val SHELF_Q = 0.7071f
    }
}
