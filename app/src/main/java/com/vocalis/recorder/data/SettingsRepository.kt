package com.vocalis.recorder.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import androidx.core.content.edit
import com.vocalis.recorder.dsp.EffectSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

enum class MicSource(val label: String, val description: String, val source: Int) {
    VOICE("Voice", "Phone's clean voice path, best with Vocalis effects", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    UNPROCESSED("Unprocessed", "Purest signal, no phone processing (not on every phone)", MediaRecorder.AudioSource.UNPROCESSED),
    STANDARD("Standard", "Default microphone path", MediaRecorder.AudioSource.MIC),
    CAMCORDER("Camcorder", "Tuned for video; may use the back mic", MediaRecorder.AudioSource.CAMCORDER),
}

data class RecorderConfig(
    val sampleRate: Int = 48_000,
    val stereo: Boolean = false,
    val micSource: MicSource = MicSource.VOICE,
    val keepOriginal: Boolean = true,
    val liveMonitor: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vocalis", Context.MODE_PRIVATE)

    private val _effects = MutableStateFlow(readEffects())
    val effects: StateFlow<EffectSettings> = _effects.asStateFlow()

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<RecorderConfig> = _config.asStateFlow()

    fun updateEffects(transform: (EffectSettings) -> EffectSettings) {
        writeEffects(_effects.updateAndGet(transform))
    }

    fun updateConfig(transform: (RecorderConfig) -> RecorderConfig) {
        val c = _config.updateAndGet(transform)
        prefs.edit {
            putInt("sampleRate", c.sampleRate)
            putBoolean("stereo", c.stereo)
            putString("micSource", c.micSource.name)
            putBoolean("keepOriginal", c.keepOriginal)
            putBoolean("liveMonitor", c.liveMonitor)
        }
    }

    private fun readConfig(): RecorderConfig {
        val d = RecorderConfig()
        return RecorderConfig(
            sampleRate = prefs.getInt("sampleRate", d.sampleRate),
            stereo = prefs.getBoolean("stereo", d.stereo),
            micSource = prefs.getString("micSource", null)
                ?.let { name -> MicSource.entries.firstOrNull { it.name == name } } ?: d.micSource,
            keepOriginal = prefs.getBoolean("keepOriginal", d.keepOriginal),
            liveMonitor = prefs.getBoolean("liveMonitor", d.liveMonitor),
        )
    }

    private fun readEffects(): EffectSettings {
        val d = EffectSettings()
        fun f(key: String, def: Float) = prefs.getFloat("fx.$key", def)
        fun b(key: String, def: Boolean) = prefs.getBoolean("fx.$key", def)
        return EffectSettings(
            inputGainDb = f("inputGainDb", d.inputGainDb),
            highPassEnabled = b("highPassEnabled", d.highPassEnabled),
            highPassHz = f("highPassHz", d.highPassHz),
            noiseReductionEnabled = b("noiseReductionEnabled", d.noiseReductionEnabled),
            noiseReductionStrength = f("noiseReductionStrength", d.noiseReductionStrength),
            breathReductionEnabled = b("breathReductionEnabled", d.breathReductionEnabled),
            breathReductionDb = f("breathReductionDb", d.breathReductionDb),
            gateEnabled = b("gateEnabled", d.gateEnabled),
            gateThresholdDb = f("gateThresholdDb", d.gateThresholdDb),
            eqEnabled = b("eqEnabled", d.eqEnabled),
            eqLowDb = f("eqLowDb", d.eqLowDb),
            eqMudDb = f("eqMudDb", d.eqMudDb),
            eqPresenceDb = f("eqPresenceDb", d.eqPresenceDb),
            eqAirDb = f("eqAirDb", d.eqAirDb),
            deEsserEnabled = b("deEsserEnabled", d.deEsserEnabled),
            deEsserThresholdDb = f("deEsserThresholdDb", d.deEsserThresholdDb),
            compressorEnabled = b("compressorEnabled", d.compressorEnabled),
            compressorThresholdDb = f("compressorThresholdDb", d.compressorThresholdDb),
            compressorRatio = f("compressorRatio", d.compressorRatio),
            compressorMakeupDb = f("compressorMakeupDb", d.compressorMakeupDb),
            limiterEnabled = b("limiterEnabled", d.limiterEnabled),
            limiterCeilingDb = f("limiterCeilingDb", d.limiterCeilingDb),
            normalizeEnabled = b("normalizeEnabled", d.normalizeEnabled),
            normalizeTargetLufs = f("normalizeTargetLufs", d.normalizeTargetLufs),
            hwNoiseSuppressor = b("hwNoiseSuppressor", d.hwNoiseSuppressor),
            hwEchoCanceler = b("hwEchoCanceler", d.hwEchoCanceler),
            hwAutoGain = b("hwAutoGain", d.hwAutoGain),
        )
    }

    private fun writeEffects(s: EffectSettings) {
        prefs.edit {
            fun f(key: String, v: Float) = putFloat("fx.$key", v)
            fun b(key: String, v: Boolean) = putBoolean("fx.$key", v)
            f("inputGainDb", s.inputGainDb)
            b("highPassEnabled", s.highPassEnabled)
            f("highPassHz", s.highPassHz)
            b("noiseReductionEnabled", s.noiseReductionEnabled)
            f("noiseReductionStrength", s.noiseReductionStrength)
            b("breathReductionEnabled", s.breathReductionEnabled)
            f("breathReductionDb", s.breathReductionDb)
            b("gateEnabled", s.gateEnabled)
            f("gateThresholdDb", s.gateThresholdDb)
            b("eqEnabled", s.eqEnabled)
            f("eqLowDb", s.eqLowDb)
            f("eqMudDb", s.eqMudDb)
            f("eqPresenceDb", s.eqPresenceDb)
            f("eqAirDb", s.eqAirDb)
            b("deEsserEnabled", s.deEsserEnabled)
            f("deEsserThresholdDb", s.deEsserThresholdDb)
            b("compressorEnabled", s.compressorEnabled)
            f("compressorThresholdDb", s.compressorThresholdDb)
            f("compressorRatio", s.compressorRatio)
            f("compressorMakeupDb", s.compressorMakeupDb)
            b("limiterEnabled", s.limiterEnabled)
            f("limiterCeilingDb", s.limiterCeilingDb)
            b("normalizeEnabled", s.normalizeEnabled)
            f("normalizeTargetLufs", s.normalizeTargetLufs)
            b("hwNoiseSuppressor", s.hwNoiseSuppressor)
            b("hwEchoCanceler", s.hwEchoCanceler)
            b("hwAutoGain", s.hwAutoGain)
        }
    }
}
