package com.vocalis.recorder.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.vocalis.recorder.ui.components.HeaderTitle
import com.vocalis.recorder.ui.components.ScreenHeader
import com.vocalis.recorder.ui.theme.Vc
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vocalis.recorder.dsp.EffectSettings
import com.vocalis.recorder.dsp.Preset
import com.vocalis.recorder.ui.components.SectionLabel
import com.vocalis.recorder.ui.theme.TabularNumbers

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffectsScreen(
    effects: EffectSettings,
    onChange: ((EffectSettings) -> EffectSettings) -> Unit,
    onPreset: (Preset) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activePreset = Preset.matching(effects.copy(inputGainDb = 0f, hwNoiseSuppressor = false, hwEchoCanceler = false, hwAutoGain = false))

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = { HeaderTitle("Sound Effects") },
                leading = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        }
        item {
            SectionLabel("Presets")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p ->
                    FilterChip(
                        selected = p == activePreset,
                        onClick = { onPreset(p) },
                        label = { Text(p.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Vc.RedSoft, selectedLabelColor = Vc.TextPrimary),
                    )
                }
            }
            Text(
                activePreset?.description ?: "Custom settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Text(
                "Changes apply live, even while recording. Use Enhance in the library to re-process older takes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }

        item {
            EffectCard("Input gain", "Boost quiet mics or tame loud ones before processing", null, {}) {
                LabeledSlider("Gain", effects.inputGainDb, -12f..24f, "%+.0f dB") { v -> onChange { it.copy(inputGainDb = v) } }
            }
        }

        item {
            EffectCard(
                "Noise reduction", "Removes steady background noise: fans, AC, hiss, traffic hum",
                effects.noiseReductionEnabled, { on -> onChange { it.copy(noiseReductionEnabled = on) } },
            ) {
                LabeledSlider("Strength", effects.noiseReductionStrength * 100, 0f..100f, "%.0f%%") { v ->
                    onChange { it.copy(noiseReductionStrength = v / 100f) }
                }
            }
        }

        item {
            EffectCard(
                "Breath reduction", "Softens breaths between phrases instead of cutting them, so speech still sounds natural",
                effects.breathReductionEnabled, { on -> onChange { it.copy(breathReductionEnabled = on) } },
            ) {
                LabeledSlider("Reduce breaths by", effects.breathReductionDb, 3f..24f, "%.0f dB") { v ->
                    onChange { it.copy(breathReductionDb = v) }
                }
            }
        }

        item {
            EffectCard(
                "Rumble filter", "High-pass that cuts wind, handling noise and traffic boom",
                effects.highPassEnabled, { on -> onChange { it.copy(highPassEnabled = on) } },
            ) {
                LabeledSlider("Cutoff", effects.highPassHz, 20f..300f, "%.0f Hz") { v -> onChange { it.copy(highPassHz = v) } }
            }
        }

        item {
            EffectCard(
                "Noise gate", "Silences the gaps between sentences",
                effects.gateEnabled, { on -> onChange { it.copy(gateEnabled = on) } },
            ) {
                LabeledSlider("Threshold", effects.gateThresholdDb, -80f..-20f, "%.0f dB") { v -> onChange { it.copy(gateThresholdDb = v) } }
            }
        }

        item {
            EffectCard(
                "Voice EQ", "Shape the tone of your voice",
                effects.eqEnabled, { on -> onChange { it.copy(eqEnabled = on) } },
            ) {
                LabeledSlider("Warmth · 120 Hz", effects.eqLowDb, -12f..12f, "%+.1f dB") { v -> onChange { it.copy(eqLowDb = v) } }
                LabeledSlider("Mud · 350 Hz", effects.eqMudDb, -12f..12f, "%+.1f dB") { v -> onChange { it.copy(eqMudDb = v) } }
                LabeledSlider("Presence · 3.2 kHz", effects.eqPresenceDb, -12f..12f, "%+.1f dB") { v -> onChange { it.copy(eqPresenceDb = v) } }
                LabeledSlider("Air · 10 kHz", effects.eqAirDb, -12f..12f, "%+.1f dB") { v -> onChange { it.copy(eqAirDb = v) } }
            }
        }

        item {
            EffectCard(
                "De-esser", "Softens harsh S and SH sounds",
                effects.deEsserEnabled, { on -> onChange { it.copy(deEsserEnabled = on) } },
            ) {
                LabeledSlider("Threshold", effects.deEsserThresholdDb, -50f..-10f, "%.0f dB") { v -> onChange { it.copy(deEsserThresholdDb = v) } }
            }
        }

        item {
            EffectCard(
                "Compressor", "Evens out loud and quiet moments for a consistent, radio-like voice",
                effects.compressorEnabled, { on -> onChange { it.copy(compressorEnabled = on) } },
            ) {
                LabeledSlider("Threshold", effects.compressorThresholdDb, -40f..0f, "%.0f dB") { v -> onChange { it.copy(compressorThresholdDb = v) } }
                LabeledSlider("Ratio", effects.compressorRatio, 1f..10f, "%.1f:1") { v -> onChange { it.copy(compressorRatio = v) } }
                LabeledSlider("Makeup gain", effects.compressorMakeupDb, 0f..18f, "%+.0f dB") { v -> onChange { it.copy(compressorMakeupDb = v) } }
            }
        }

        item {
            EffectCard(
                "Limiter", "Brick-wall safety so nothing ever clips",
                effects.limiterEnabled, { on -> onChange { it.copy(limiterEnabled = on) } },
            ) {
                LabeledSlider("Ceiling", effects.limiterCeilingDb, -6f..0f, "%.1f dB") { v -> onChange { it.copy(limiterCeilingDb = v) } }
            }
        }

        item {
            EffectCard(
                "Loudness normalization", "Matches platform loudness after you stop recording",
                effects.normalizeEnabled, { on -> onChange { it.copy(normalizeEnabled = on) } },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-14f, -16f, -19f, -23f).forEach { lufs ->
                        FilterChip(
                            selected = effects.normalizeTargetLufs == lufs,
                            onClick = { onChange { it.copy(normalizeTargetLufs = lufs) } },
                            label = { Text("%.0f".format(lufs)) },
                        )
                    }
                }
                Text(
                    "%.0f LUFS · %s".format(
                        effects.normalizeTargetLufs,
                        when (effects.normalizeTargetLufs) {
                            -14f -> "YouTube, Spotify, Instagram"
                            -16f -> "Apple Podcasts, most podcast hosts"
                            -19f -> "Mono podcast standard"
                            -23f -> "EBU R128 broadcast"
                            else -> "Custom"
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun EffectCard(
    title: String,
    subtitle: String,
    enabled: Boolean?,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = Vc.Surface, border = BorderStroke(1.dp, Vc.Border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (enabled != null) Switch(checked = enabled, onCheckedChange = onToggle)
            }
            AnimatedVisibility(visible = enabled != false) {
                Column(Modifier.padding(top = 8.dp)) { content() }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format.format(value), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), color = MaterialTheme.colorScheme.secondary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}
