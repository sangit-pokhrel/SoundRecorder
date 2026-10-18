package com.vocalis.recorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocalis.recorder.audio.RecorderState
import com.vocalis.recorder.audio.RecorderStatus
import com.vocalis.recorder.data.Recording
import com.vocalis.recorder.data.RecorderConfig
import com.vocalis.recorder.dsp.EffectSettings
import com.vocalis.recorder.dsp.Preset
import com.vocalis.recorder.ui.components.HeroRecorder
import com.vocalis.recorder.ui.components.LevelMeter
import com.vocalis.recorder.ui.components.RecordingActions
import com.vocalis.recorder.ui.components.RecordingDialogState
import com.vocalis.recorder.ui.components.RecordingDialogs
import com.vocalis.recorder.ui.components.RecordingRow
import com.vocalis.recorder.ui.components.ScreenHeader
import com.vocalis.recorder.ui.components.StripCell
import com.vocalis.recorder.ui.components.StudioCard
import com.vocalis.recorder.ui.components.formatDuration
import com.vocalis.recorder.ui.theme.TabularNumbers
import com.vocalis.recorder.ui.theme.Vc

class RecorderActions(
    val onRecord: () -> Unit,
    val onStop: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onMark: () -> Unit,
    val onRelearn: () -> Unit,
)

/** Home-screen quick modes: each one is a preset, shown with the icon from the design. */
private val quickModes = listOf(
    Triple("Voice Notes", Icons.AutoMirrored.Rounded.List, Preset.PODCAST),
    Triple("Meeting", Icons.Rounded.Groups, Preset.LECTURE),
    Triple("Music", Icons.Rounded.MusicNote, Preset.MUSIC),
)

@Composable
fun HomeScreen(
    state: RecorderState,
    effects: EffectSettings,
    config: RecorderConfig,
    recent: List<Recording>,
    playingFile: java.io.File?,
    recorder: RecorderActions,
    recordingActions: RecordingActions,
    onPreset: (Preset) -> Unit,
    onConfig: ((RecorderConfig) -> RecorderConfig) -> Unit,
    onOpenEffects: () -> Unit,
    onSearch: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state.status == RecorderStatus.RECORDING || state.status == RecorderStatus.PAUSED
    val finishing = state.status == RecorderStatus.FINISHING
    val dialogs = remember { RecordingDialogState() }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = {
                    Text(
                        buildAnnotatedString {
                            append("Vocalis ")
                            withStyle(SpanStyle(color = Vc.Lavender)) { append("Recorder") }
                        },
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Vc.TextPrimary,
                    )
                },
                subtitle = "Studio-clean voice, right from your phone",
                leading = { IconButton(onClick = onOpenEffects) { Icon(Icons.Rounded.Tune, "Sound effects", tint = Vc.TextPrimary) } },
                trailing = { IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search recordings", tint = Vc.TextPrimary) } },
            )
        }

        item {
            HeroRecorder(
                recording = active,
                paused = state.status == RecorderStatus.PAUSED,
                levels = state.waveform,
                enabled = !finishing,
                onClick = if (active) recorder.onStop else recorder.onRecord,
            )
        }

        item { StatusBlock(state, effects, config, active, finishing) }

        item {
            if (active) LiveCard(state, effects, recorder) else ModeStrip(effects, config, onPreset, onConfig)
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Recordings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Vc.TextPrimary, modifier = Modifier.weight(1f))
                if (recent.isNotEmpty()) {
                    TextButton(onClick = onSeeAll) {
                        Text("See All", color = Vc.Red, fontSize = 16.sp)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Vc.Red)
                    }
                }
            }
        }

        if (recent.isEmpty()) {
            item {
                StudioCard {
                    Text(
                        "Your recordings will appear here. Tap the mic and stay quiet for a second so Vocalis can learn your room.",
                        color = Vc.TextSecondary,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        } else {
            items(recent, key = { it.file.path }) { rec ->
                RecordingRow(rec, isPlaying = playingFile == rec.file, actions = recordingActions, dialogs = dialogs)
            }
        }
    }
    RecordingDialogs(dialogs, recordingActions)
}

@Composable
private fun StatusBlock(state: RecorderState, effects: EffectSettings, config: RecorderConfig, active: Boolean, finishing: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            finishing -> {
                Text("Polishing loudness…", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Vc.TextPrimary)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.finishingProgress },
                    modifier = Modifier.fillMaxWidth(0.6f),
                    color = Vc.Red,
                    trackColor = Vc.SurfaceHigher,
                )
            }
            active -> {
                Text(
                    formatDuration(state.elapsedMs, withTenths = true),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    style = TabularNumbers,
                    color = Vc.TextPrimary,
                )
                val (text, color) = when {
                    state.status == RecorderStatus.PAUSED -> "Paused" to Vc.TextSecondary
                    state.learningNoise -> "Learning room noise… stay quiet" to Vc.Amber
                    else -> "Recording" to Vc.Red
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.status == RecorderStatus.RECORDING && !state.learningNoise) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Vc.Red))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(text, fontSize = 16.sp, color = color)
                }
            }
            else -> {
                Text("Tap to Record", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Vc.TextPrimary)
                Spacer(Modifier.height(4.dp))
                val cleanup = buildList {
                    if (effects.noiseReductionEnabled) add("Noise")
                    if (effects.breathReductionEnabled) add("Breath")
                }
                Text(
                    buildString {
                        append(if (config.sampleRate >= 48_000) "High quality" else "CD quality")
                        append("  •  ")
                        append(if (cleanup.isEmpty()) "No time limit" else cleanup.joinToString(" & ") + " cleanup on")
                    },
                    fontSize = 16.sp,
                    color = Vc.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ModeStrip(
    effects: EffectSettings,
    config: RecorderConfig,
    onPreset: (Preset) -> Unit,
    onConfig: ((RecorderConfig) -> RecorderConfig) -> Unit,
) {
    val activePreset = Preset.matching(effects.copy(inputGainDb = 0f, hwNoiseSuppressor = false, hwEchoCanceler = false, hwAutoGain = false))
    var qualityMenu by remember { mutableStateOf(false) }
    StudioCard(Modifier.padding(top = 8.dp)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                StripCell(
                    label = "Quality",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { qualityMenu = true },
                    trailingLabel = { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Vc.TextSecondary, modifier = Modifier.size(18.dp)) },
                ) { Icon(Icons.Rounded.GraphicEq, null, tint = Vc.TextPrimary, modifier = Modifier.size(28.dp)) }
                DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }, containerColor = Vc.SurfaceHigh) {
                    QualityItem("48 kHz  ·  Video & podcasts", config.sampleRate == 48_000) { onConfig { it.copy(sampleRate = 48_000) } }
                    QualityItem("44.1 kHz  ·  Music / CD", config.sampleRate == 44_100) { onConfig { it.copy(sampleRate = 44_100) } }
                    HorizontalDivider(color = Vc.Border)
                    QualityItem("Mono  ·  best for voice", !config.stereo) { onConfig { it.copy(stereo = false) } }
                    QualityItem("Stereo", config.stereo) { onConfig { it.copy(stereo = true) } }
                }
            }
            quickModes.forEach { (label, icon, preset) ->
                VerticalDivider(Modifier.height(48.dp), color = Vc.Border)
                StripCell(
                    label = label,
                    selected = activePreset == preset,
                    onClick = { onPreset(preset) },
                    modifier = Modifier.weight(1f),
                ) { Icon(icon, null, tint = if (activePreset == preset) Vc.Red else Vc.TextPrimary, modifier = Modifier.size(28.dp)) }
            }
        }
    }
}

@Composable
private fun QualityItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        trailingIcon = { if (checked) Icon(Icons.Rounded.Check, null, tint = Vc.Red) },
    )
}

@Composable
private fun LiveCard(state: RecorderState, effects: EffectSettings, recorder: RecorderActions) {
    StudioCard(Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            LevelMeter(peakDb = state.peakDb, clipped = state.clipped)
            if (state.clipped) {
                Text("Too loud: move the mic back a little", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Denoise", if (effects.noiseReductionEnabled) formatDb(state.noiseReductionDb.coerceAtLeast(0f)) else "Off", Icons.Rounded.GraphicEq, Modifier.weight(1f))
                Stat("Breaths", if (effects.breathReductionEnabled) state.breathsSoftened.toString() else "Off", Icons.Rounded.Air, Modifier.weight(1f))
                Stat("Leveler", if (effects.compressorEnabled) formatDb(state.compressionDb) else "Off", Icons.Rounded.Compress, Modifier.weight(1f))
                Stat("Markers", state.markersMs.size.toString(), Icons.Rounded.Flag, Modifier.weight(1f))
            }
            HorizontalDivider(color = Vc.Border, modifier = Modifier.padding(vertical = 14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val paused = state.status == RecorderStatus.PAUSED
                Control("Mark", Icons.Rounded.Flag, recorder.onMark)
                Control(if (paused) "Resume" else "Pause", if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) recorder.onResume else recorder.onPause)
                Control("Re-learn noise", Icons.Rounded.Refresh, recorder.onRelearn, enabled = effects.noiseReductionEnabled)
            }
        }
    }
}

/** Whole-dB readout that never shows "-0 dB". */
private fun formatDb(db: Float): String {
    val rounded = Math.round(db)
    return "$rounded dB".replace("-0 dB", "0 dB")
}

@Composable
private fun Stat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Vc.Lavender, modifier = Modifier.size(18.dp))
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Vc.TextPrimary, style = TabularNumbers)
        Text(label, fontSize = 12.sp, color = Vc.TextSecondary)
    }
}

@Composable
private fun Control(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(54.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Vc.SurfaceHigher, contentColor = Vc.TextPrimary),
        ) { Icon(icon, label) }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = Vc.TextSecondary)
    }
}
