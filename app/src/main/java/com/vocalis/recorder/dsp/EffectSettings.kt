package com.vocalis.recorder.dsp

/** Every user-tweakable parameter of the processing chain. */
data class EffectSettings(
    val inputGainDb: Float = 0f,

    val highPassEnabled: Boolean = true,
    val highPassHz: Float = 80f,

    val noiseReductionEnabled: Boolean = true,
    val noiseReductionStrength: Float = 0.6f,

    val breathReductionEnabled: Boolean = true,
    val breathReductionDb: Float = 12f,

    val gateEnabled: Boolean = false,
    val gateThresholdDb: Float = -50f,

    val eqEnabled: Boolean = true,
    val eqLowDb: Float = 1.5f,
    val eqMudDb: Float = -2f,
    val eqPresenceDb: Float = 2.5f,
    val eqAirDb: Float = 1.5f,

    val deEsserEnabled: Boolean = true,
    val deEsserThresholdDb: Float = -30f,

    val compressorEnabled: Boolean = true,
    val compressorThresholdDb: Float = -22f,
    val compressorRatio: Float = 3f,
    val compressorMakeupDb: Float = 4f,

    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1f,

    val normalizeEnabled: Boolean = true,
    val normalizeTargetLufs: Float = -16f,

    // Device-provided effects (android.media.audiofx); availability varies by phone.
    val hwNoiseSuppressor: Boolean = false,
    val hwEchoCanceler: Boolean = false,
    val hwAutoGain: Boolean = false,
)

enum class Preset(val label: String, val description: String, val settings: EffectSettings) {
    PODCAST(
        "Podcast",
        "Clean, warm, broadcast-loud voice (-16 LUFS)",
        EffectSettings(),
    ),
    VOICE_OVER(
        "Voice-over",
        "Tight, polished narration for videos and audiobooks",
        EffectSettings(
            noiseReductionStrength = 0.75f,
            breathReductionDb = 15f,
            gateEnabled = true, gateThresholdDb = -48f,
            eqLowDb = 2f, eqMudDb = -3f, eqPresenceDb = 3f, eqAirDb = 2.5f,
            compressorThresholdDb = -24f, compressorRatio = 4f, compressorMakeupDb = 6f,
            normalizeTargetLufs = -16f,
        ),
    ),
    VLOG_OUTDOOR(
        "Outdoor / Vlog",
        "Fights wind rumble and traffic; loud enough for YouTube (-14 LUFS)",
        EffectSettings(
            highPassHz = 150f,
            noiseReductionStrength = 0.9f,
            eqLowDb = 0f, eqMudDb = -3f, eqPresenceDb = 3.5f, eqAirDb = 0f,
            compressorThresholdDb = -24f, compressorRatio = 4f, compressorMakeupDb = 6f,
            normalizeTargetLufs = -14f,
        ),
    ),
    INTERVIEW(
        "Interview",
        "Natural sound for two people around one phone",
        EffectSettings(
            noiseReductionStrength = 0.5f,
            breathReductionDb = 9f,
            eqLowDb = 0f, eqMudDb = -2f, eqPresenceDb = 2f, eqAirDb = 1f,
            compressorThresholdDb = -26f, compressorRatio = 2.5f, compressorMakeupDb = 5f,
        ),
    ),
    LECTURE(
        "Lecture / Meeting",
        "Pulls a distant speaker forward in a noisy room",
        EffectSettings(
            highPassHz = 120f,
            noiseReductionStrength = 0.85f,
            eqLowDb = -1f, eqMudDb = -4f, eqPresenceDb = 4f, eqAirDb = 0f,
            deEsserEnabled = false,
            compressorThresholdDb = -30f, compressorRatio = 4f, compressorMakeupDb = 10f,
        ),
    ),
    MUSIC(
        "Music",
        "Light touch: rumble filter and peak safety only",
        EffectSettings(
            highPassHz = 30f,
            noiseReductionEnabled = false,
            breathReductionEnabled = false,
            eqEnabled = false, eqLowDb = 0f, eqMudDb = 0f, eqPresenceDb = 0f, eqAirDb = 0f,
            deEsserEnabled = false,
            compressorEnabled = false,
            limiterCeilingDb = -0.5f,
            normalizeEnabled = false,
        ),
    ),
    RAW(
        "Raw",
        "No processing at all",
        EffectSettings(
            highPassEnabled = false,
            noiseReductionEnabled = false,
            breathReductionEnabled = false,
            eqEnabled = false, eqLowDb = 0f, eqMudDb = 0f, eqPresenceDb = 0f, eqAirDb = 0f,
            deEsserEnabled = false,
            compressorEnabled = false,
            limiterEnabled = false,
            normalizeEnabled = false,
        ),
    );

    companion object {
        fun matching(settings: EffectSettings): Preset? = entries.firstOrNull { it.settings == settings }
    }
}
