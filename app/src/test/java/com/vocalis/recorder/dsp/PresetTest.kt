package com.vocalis.recorder.dsp

import com.vocalis.recorder.ui.components.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTest {

    @Test
    fun everyPresetIsDistinctAndRecognisable() {
        assertEquals(Preset.entries.size, Preset.entries.map { it.settings }.toSet().size)
        for (p in Preset.entries) assertEquals(p, Preset.matching(p.settings))
    }

    @Test
    fun tweakedSettingsAreCustom() {
        assertNull(Preset.matching(Preset.PODCAST.settings.copy(eqAirDb = 6f)))
    }

    @Test
    fun rawReallyBypassesEverything() {
        val raw = Preset.RAW.settings
        val chain = ProcessingChain(48_000, 1, raw)
        assertEquals(0, chain.latencySamples)
        val signal = FloatArray(4800) { ((it % 97) - 48) / 100f }
        val copy = signal.copyOf()
        chain.process(copy, copy.size)
        assertTrue(signal.contentEquals(copy))
    }

    @Test
    fun voicePresetsCleanUpBreathsMusicDoesNot() {
        assertTrue(Preset.PODCAST.settings.breathReductionEnabled)
        assertTrue(Preset.VOICE_OVER.settings.breathReductionEnabled)
        assertFalse(Preset.MUSIC.settings.breathReductionEnabled)
    }

    @Test
    fun chainLatencyIncludesEveryLookaheadStage() {
        val s = Preset.PODCAST.settings
        val expected = SpectralDenoiser(48_000).latencySamples +
            BreathReducer(48_000).latencySamples +
            Limiter(48_000).latencySamples
        assertEquals(expected, ProcessingChain(48_000, 1, s).latencySamples)
    }

    @Test
    fun formatsDurations() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("1:00:01", formatDuration(3_601_000))
        assertEquals("00:14.8", formatDuration(14_850, withTenths = true))
        assertEquals("negative durations clamp to zero", "00:00", formatDuration(-5))
    }
}
