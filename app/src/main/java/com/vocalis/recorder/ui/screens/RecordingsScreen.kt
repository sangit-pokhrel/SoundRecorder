package com.vocalis.recorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocalis.recorder.data.Recording
import com.vocalis.recorder.ui.PlayerState
import com.vocalis.recorder.ui.components.FileWaveform
import com.vocalis.recorder.ui.components.HeaderTitle
import com.vocalis.recorder.ui.components.RecordingActions
import com.vocalis.recorder.ui.components.RecordingDialogState
import com.vocalis.recorder.ui.components.RecordingDialogs
import com.vocalis.recorder.ui.components.RecordingRow
import com.vocalis.recorder.ui.components.ScreenHeader
import com.vocalis.recorder.ui.components.formatDuration
import com.vocalis.recorder.ui.components.formatSize
import com.vocalis.recorder.ui.theme.TabularNumbers
import com.vocalis.recorder.ui.theme.Vc

@Composable
fun RecordingsScreen(
    recordings: List<Recording>,
    playingFile: java.io.File?,
    actions: RecordingActions,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val dialogs = remember { RecordingDialogState() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            focus.requestFocus()
            onSearchFocused()
        }
    }
    val filtered = if (query.isBlank()) recordings else recordings.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val totalMs = recordings.sumOf { it.durationMs }
    val totalBytes = recordings.sumOf { it.sizeBytes }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = { HeaderTitle("Recordings") },
                subtitle = if (recordings.isEmpty()) null
                else "${recordings.size} takes  •  ${formatDuration(totalMs)}  •  ${formatSize(totalBytes)}",
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search recordings") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Vc.Surface,
                    unfocusedContainerColor = Vc.Surface,
                    unfocusedBorderColor = Vc.Border,
                    focusedBorderColor = Vc.Red,
                    unfocusedLeadingIconColor = Vc.TextSecondary,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (filtered.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(56.dp), tint = Vc.TextMuted)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (recordings.isEmpty()) "No recordings yet" else "No matches for \"$query\"",
                        fontSize = 18.sp, color = Vc.TextPrimary,
                    )
                    if (recordings.isEmpty()) {
                        Text("Your takes appear here, ready to play, enhance, export and share.", color = Vc.TextSecondary)
                    }
                }
            }
        } else {
            items(filtered, key = { it.file.path }) { rec ->
                RecordingRow(rec, isPlaying = playingFile == rec.file, actions = actions, dialogs = dialogs)
            }
        }
    }
    RecordingDialogs(dialogs, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    state: PlayerState,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkip: (Long) -> Unit,
    onOriginal: (Boolean) -> Unit,
) {
    val rec = state.recording ?: return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Vc.Surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(rec.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(rec.formatLabel, fontSize = 14.sp, color = Vc.TextSecondary)
            Spacer(Modifier.height(24.dp))

            val duration = state.durationMs.coerceAtLeast(1)
            FileWaveform(
                peaks = state.peaks,
                progress = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                markers = rec.markersMs.map { it.toFloat() / duration },
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(state.positionMs), style = TabularNumbers, color = Vc.TextSecondary)
                Text(formatDuration(state.durationMs), style = TabularNumbers, color = Vc.TextSecondary)
            }

            if (rec.original != null) {
                Spacer(Modifier.height(18.dp))
                Text("A/B compare", fontSize = 13.sp, color = Vc.TextSecondary)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !state.playingOriginal,
                        onClick = { onOriginal(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = Vc.RedSoft, activeContentColor = Vc.TextPrimary),
                    ) { Text("Processed") }
                    SegmentedButton(
                        selected = state.playingOriginal,
                        onClick = { onOriginal(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = Vc.RedSoft, activeContentColor = Vc.TextPrimary),
                    ) { Text("Original") }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSkip(-10_000) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Replay10, "Back 10 seconds", Modifier.size(30.dp))
                }
                Spacer(Modifier.width(28.dp))
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Vc.Red),
                ) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(38.dp))
                }
                Spacer(Modifier.width(28.dp))
                IconButton(onClick = { onSkip(10_000) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Forward10, "Forward 10 seconds", Modifier.size(30.dp))
                }
            }
        }
    }
}
