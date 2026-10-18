package com.vocalis.recorder.ui.screens

import android.media.audiofx.AcousticEchoCanceler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import com.vocalis.recorder.BuildConfig
import com.vocalis.recorder.ui.components.HeaderTitle
import com.vocalis.recorder.ui.components.ScreenHeader
import com.vocalis.recorder.ui.theme.Vc
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vocalis.recorder.data.MicSource
import com.vocalis.recorder.data.RecorderConfig
import com.vocalis.recorder.dsp.EffectSettings
import com.vocalis.recorder.ui.components.SectionLabel

@Composable
fun SettingsScreen(
    config: RecorderConfig,
    effects: EffectSettings,
    recordingActive: Boolean,
    storagePath: String,
    onConfig: ((RecorderConfig) -> RecorderConfig) -> Unit,
    onEffects: ((EffectSettings) -> EffectSettings) -> Unit,
    onOpenEffects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hwAvailable = remember {
        Triple(NoiseSuppressor.isAvailable(), AcousticEchoCanceler.isAvailable(), AutomaticGainControl.isAvailable())
    }
    val editable = !recordingActive

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader(title = { HeaderTitle("Settings") }) }
        item {
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenEffects).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Vc.RedSoft),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Tune, null, tint = Vc.Red) }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text("Sound effects", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Noise & breath reduction, EQ, compressor, loudness",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Vc.TextSecondary)
                }
            }
        }
        if (recordingActive) {
            item {
                Text(
                    "Recording settings can't change mid-take. They apply to your next recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        item {
            SectionLabel("Quality")
            Card {
                Text("Sample rate", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    listOf(44_100, 48_000).forEachIndexed { i, rate ->
                        SegmentedButton(
                            selected = config.sampleRate == rate,
                            onClick = { onConfig { it.copy(sampleRate = rate) } },
                            enabled = editable,
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(if (rate == 48_000) "48 kHz · Video" else "44.1 kHz · Music") }
                    }
                }
                Text("Channels", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    SegmentedButton(!config.stereo, { onConfig { it.copy(stereo = false) } }, SegmentedButtonDefaults.itemShape(0, 2), enabled = editable) { Text("Mono · Voice") }
                    SegmentedButton(config.stereo, { onConfig { it.copy(stereo = true) } }, SegmentedButtonDefaults.itemShape(1, 2), enabled = editable) { Text("Stereo") }
                }
                Text(
                    "Recordings are saved as lossless 16-bit WAV. Export to M4A from the library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        item {
            SectionLabel("Microphone")
            Card {
                MicSource.entries.forEach { src ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(config.micSource == src, enabled = editable, role = Role.RadioButton) { onConfig { it.copy(micSource = src) } }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = config.micSource == src, onClick = null, enabled = editable)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(src.label, style = MaterialTheme.typography.bodyLarge)
                            Text(src.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "External USB and wired mics are used automatically when plugged in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item {
            SectionLabel("Workflow")
            Card {
                ToggleRow(
                    "Keep original take",
                    "Saves an unprocessed copy for A/B compare and re-processing later",
                    config.keepOriginal, editable,
                ) { v -> onConfig { it.copy(keepOriginal = v) } }
                ToggleRow(
                    "Live monitoring",
                    "Hear the processed sound while recording. Use headphones to avoid feedback.",
                    config.liveMonitor, editable,
                ) { v -> onConfig { it.copy(liveMonitor = v) } }
            }
        }
        item {
            SectionLabel("Phone's built-in effects")
            Card {
                ToggleRow("Hardware noise suppressor", availability(hwAvailable.first, "Extra suppression from your phone's chip"),
                    effects.hwNoiseSuppressor && hwAvailable.first, editable && hwAvailable.first) { v -> onEffects { it.copy(hwNoiseSuppressor = v) } }
                ToggleRow("Echo canceller", availability(hwAvailable.second, "Reduces room echo and speaker bleed"),
                    effects.hwEchoCanceler && hwAvailable.second, editable && hwAvailable.second) { v -> onEffects { it.copy(hwEchoCanceler = v) } }
                ToggleRow("Automatic gain", availability(hwAvailable.third, "Phone adjusts mic level (can pump; usually leave off)"),
                    effects.hwAutoGain && hwAvailable.third, editable && hwAvailable.third) { v -> onEffects { it.copy(hwAutoGain = v) } }
            }
        }
        item {
            SectionLabel("About")
            Card {
                Text("Vocalis ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Free and open-source (MIT). All processing happens on your phone. No accounts, ads or tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Storage", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text(storagePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun availability(available: Boolean, description: String) =
    if (available) description else "Not available on this phone"

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Vc.Surface, border = BorderStroke(1.dp, Vc.Border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
