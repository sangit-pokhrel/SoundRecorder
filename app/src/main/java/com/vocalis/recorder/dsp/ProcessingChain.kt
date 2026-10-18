package com.vocalis.recorder.dsp

/**
 * The full voice chain for one recording:
 *
 *   input gain → high-pass → noise reduction → breath reduction → gate → EQ → de-esser → compressor → limiter
 *
 * Works on interleaved multi-channel audio by running an independent mono chain per channel.
 * [configure] can be called between blocks (e.g. while recording) without losing filter state.
 */
class ProcessingChain(sampleRate: Int, private val channels: Int, settings: EffectSettings) {

    private class ChannelStrip(sampleRate: Int) {
        val highPass = HighPassFilter(sampleRate, 80f)
        val denoiser = SpectralDenoiser(sampleRate)
        val breath = BreathReducer(sampleRate)
        val gate = NoiseGate(sampleRate)
        val eq = VoiceEqualizer(sampleRate)
        val deEsser = DeEsser(sampleRate)
        val compressor = Compressor(sampleRate)
        val limiter = Limiter(sampleRate)
    }

    private val strips = Array(channels) { ChannelStrip(sampleRate) }
    private var scratch = FloatArray(4096)

    @Volatile var settings: EffectSettings = settings
        private set

    init {
        configure(settings)
    }

    val latencySamples: Int
        get() {
            val s = settings
            val strip = strips[0]
            return (if (s.noiseReductionEnabled) strip.denoiser.latencySamples else 0) +
                (if (s.breathReductionEnabled) strip.breath.latencySamples else 0) +
                (if (s.limiterEnabled) strip.limiter.latencySamples else 0)
        }

    /** Energy currently removed by noise reduction (dB), averaged over channels. */
    val noiseReductionDb: Float
        get() = if (!settings.noiseReductionEnabled) 0f else strips.map { it.denoiser.reductionDb }.average().toFloat()

    /** Breaths softened so far in this take (channel 0 is representative). */
    val breathsDetected: Int
        get() = strips[0].breath.breathsDetected

    val compressorGainReductionDb: Float
        get() = if (!settings.compressorEnabled) 0f else strips.minOf { it.compressor.gainReductionDb }

    fun configure(s: EffectSettings) {
        settings = s
        for (strip in strips) {
            strip.highPass.cutoffHz = s.highPassHz
            strip.denoiser.strength = s.noiseReductionStrength
            strip.breath.reductionDb = s.breathReductionDb
            strip.gate.thresholdDb = s.gateThresholdDb
            strip.eq.configure(s.eqLowDb, s.eqMudDb, s.eqPresenceDb, s.eqAirDb)
            strip.deEsser.thresholdDb = s.deEsserThresholdDb
            strip.compressor.thresholdDb = s.compressorThresholdDb
            strip.compressor.ratio = s.compressorRatio
            strip.compressor.makeupDb = s.compressorMakeupDb
            strip.limiter.ceilingDb = s.limiterCeilingDb
        }
    }

    fun relearnNoise() = strips.forEach { it.denoiser.relearn() }

    fun process(interleaved: FloatArray, frames: Int) {
        val s = settings
        val inputGain = dbToLinear(s.inputGainDb)
        if (channels == 1) {
            if (inputGain != 1f) for (i in 0 until frames) interleaved[i] *= inputGain
            processStrip(strips[0], s, interleaved, frames)
            return
        }
        if (scratch.size < frames) scratch = FloatArray(frames)
        for (c in 0 until channels) {
            for (f in 0 until frames) scratch[f] = interleaved[f * channels + c] * inputGain
            processStrip(strips[c], s, scratch, frames)
            for (f in 0 until frames) interleaved[f * channels + c] = scratch[f]
        }
    }

    private fun processStrip(strip: ChannelStrip, s: EffectSettings, buf: FloatArray, n: Int) {
        if (s.highPassEnabled) strip.highPass.process(buf, 0, n)
        if (s.noiseReductionEnabled) strip.denoiser.process(buf, 0, n)
        if (s.breathReductionEnabled) strip.breath.process(buf, 0, n)
        if (s.gateEnabled) strip.gate.process(buf, 0, n)
        if (s.eqEnabled) strip.eq.process(buf, 0, n)
        if (s.deEsserEnabled) strip.deEsser.process(buf, 0, n)
        if (s.compressorEnabled) strip.compressor.process(buf, 0, n)
        if (s.limiterEnabled) strip.limiter.process(buf, 0, n)
    }
}
