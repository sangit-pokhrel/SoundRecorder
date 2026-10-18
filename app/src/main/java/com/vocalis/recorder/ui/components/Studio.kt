package com.vocalis.recorder.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocalis.recorder.data.Recording
import com.vocalis.recorder.data.RecordingNames
import com.vocalis.recorder.ui.theme.TabularNumbers
import com.vocalis.recorder.ui.theme.Vc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The home hero: a symmetric waveform (red at the centre, fading to slate at the edges) with the
 * glowing record orb in front. Idle, the waveform breathes gently; while recording the newest
 * levels sit in the middle and older ones ripple outward.
 */
@Composable
fun HeroRecorder(
    recording: Boolean,
    paused: Boolean,
    levels: List<Float>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(250.dp)) {
        HeroWaveform(
            levels = levels,
            live = recording,
            dimmed = paused,
            modifier = Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter),
        )
        RecordOrb(
            recording = recording,
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp),
        )
    }
}

private const val BAR_COUNT = 57

private val barJitter = Random(42).let { r -> FloatArray(BAR_COUNT) { 0.35f + 0.65f * r.nextFloat() } }

@Composable
private fun HeroWaveform(levels: List<Float>, live: Boolean, dimmed: Boolean, modifier: Modifier) {
    // Idle, the waveform is a still image: an endless animation would redraw 60 times a second
    // and drain the battery while the app just sits open. While recording it redraws only
    // when new levels arrive (~20 Hz).
    Canvas(modifier) {
        val center = BAR_COUNT / 2
        val slot = size.width / BAR_COUNT
        val stroke = max(2f, slot * 0.32f)
        val mid = size.height / 2
        for (i in 0 until BAR_COUNT) {
            val d = abs(i - center).toFloat() / center // 0 at centre, 1 at the edges
            val envelope = 0.18f + 0.82f * exp(-(d * 1.9f) * (d * 1.9f))
            val amount = if (live) {
                val idx = levels.size - 1 - abs(i - center)
                val level = if (idx >= 0) levels[idx] else 0f
                0.08f + 0.92f * sqrt(level.coerceIn(0f, 1f)) * 1.4f
            } else {
                0.55f + 0.18f * sin(i * 0.55f)
            }
            val h = (size.height * envelope * barJitter[i] * amount.coerceAtMost(1f)).coerceAtLeast(3f)
            val redness = 1f - ((d - 0.15f) / 0.55f).coerceIn(0f, 1f)
            val color = lerp(Vc.WaveIdle, Vc.Red, if (dimmed) redness * 0.3f else redness)
            val alpha = 1f - 0.65f * ((d - 0.6f) / 0.4f).coerceIn(0f, 1f)
            val x = slot * i + slot / 2
            drawLine(
                color.copy(alpha = alpha),
                Offset(x, mid - h / 2), Offset(x, mid + h / 2),
                strokeWidth = stroke, cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun RecordOrb(recording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    // The glow pulses only while recording (as a "live" cue); idle it stays still to save battery.
    // The value is read in the graphics layer, so the pulse redraws without recomposing.
    val glow = if (recording) {
        rememberInfiniteTransition(label = "orb").animateFloat(
            initialValue = 1f, targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "glow",
        )
    } else {
        null
    }
    Box(modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { val s = glow?.value ?: 1f; scaleX = s; scaleY = s }) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Vc.Red.copy(alpha = 0.20f), Color.Transparent), radius = r), r)
            drawCircle(Color(0xFF2A1219).copy(alpha = 0.9f), r * 0.82f)
            drawCircle(Color(0xFF3A1820), r * 0.68f)
            drawCircle(Brush.radialGradient(listOf(Vc.Red.copy(alpha = 0.55f), Color.Transparent), radius = r * 0.66f), r * 0.66f)
        }
        Box(
            Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(Vc.RecordGradient)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.material3.ripple(color = Color.White),
                    onClick = onClick,
                )
                .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(Color.White))
            } else {
                Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
    }
}

/** Centered screen title with optional icon actions on either side. */
@Composable
fun ScreenHeader(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            title()
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Vc.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

@Composable
fun HeaderTitle(text: String) {
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Vc.TextPrimary)
}

data class NavItem(val label: String, val icon: ImageVector)

/** Floating rounded bottom bar with a soft red pill behind the selected item. */
@Composable
fun VocalisBottomBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Vc.Surface,
        border = BorderStroke(1.dp, Vc.Border),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(78.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                val active = index == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 62.dp, height = 36.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) Vc.RedSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, null, tint = if (active) Vc.Red else Vc.TextSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.label,
                        fontSize = 13.sp,
                        color = if (active) Vc.Red else Vc.TextSecondary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Actions available on a recording, wired by the screen that hosts the list. */
class RecordingActions(
    val onOpen: (Recording) -> Unit,
    val onEnhance: (Recording) -> Unit,
    val onExportM4a: (Recording) -> Unit,
    val onSaveToMusic: (Recording) -> Unit,
    val onShare: (Recording) -> Unit,
    val onRename: (Recording, String) -> Unit,
    val onDelete: (Recording) -> Unit,
)

@Stable
class RecordingDialogState {
    var renaming by mutableStateOf<Recording?>(null)
    var deleting by mutableStateOf<Recording?>(null)
}

@Composable
fun RecordingDialogs(state: RecordingDialogState, actions: RecordingActions) {
    state.renaming?.let { rec ->
        var name by remember(rec) { mutableStateOf(RecordingNames.editablePart(rec.name)) }
        val save = { actions.onRename(rec, name); state.renaming = null }
        AlertDialog(
            onDismissRequest = { state.renaming = null },
            containerColor = Vc.SurfaceHigh,
            title = { Text("Rename") },
            text = { VocalisNameField(name, { name = it }, placeholder = "name", error = null, onDone = save) },
            confirmButton = { TextButton(onClick = save, enabled = name.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { state.renaming = null }) { Text("Cancel", color = Vc.TextSecondary) } },
        )
    }
    state.deleting?.let { rec ->
        AlertDialog(
            onDismissRequest = { state.deleting = null },
            containerColor = Vc.SurfaceHigh,
            title = { Text("Delete recording?") },
            text = { Text("\"${rec.name}\" and its original take will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { actions.onDelete(rec); state.deleting = null }) { Text("Delete", color = Vc.Red) } },
            dismissButton = { TextButton(onClick = { state.deleting = null }) { Text("Cancel", color = Vc.TextSecondary) } },
        )
    }
}

/** A recording card: round play button, title, date and time, duration and an actions menu. */
@Composable
fun RecordingRow(
    rec: Recording,
    isPlaying: Boolean,
    actions: RecordingActions,
    dialogs: RecordingDialogState,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isPlaying) Vc.SurfaceHigh else Vc.Surface,
        border = if (isPlaying) BorderStroke(1.dp, Vc.Red.copy(alpha = 0.5f)) else null,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { actions.onOpen(rec) },
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(if (isPlaying) Vc.Red else Vc.SurfaceHigher),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else if (rec.isWav) Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote,
                    if (isPlaying) "Playing" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rec.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Vc.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val date = Date(rec.modifiedAt)
                val locale = Locale.getDefault()
                Text(
                    buildString {
                        append(SimpleDateFormat("d MMM yyyy", locale).format(date))
                        append("  •  ")
                        append(SimpleDateFormat("hh:mm a", locale).format(date))
                        if (!rec.isWav) append("  •  M4A")
                    },
                    fontSize = 14.sp,
                    color = Vc.TextSecondary,
                    maxLines = 1,
                )
            }
            Text(
                formatDuration(rec.durationMs),
                style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                color = Vc.TextSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions", tint = Vc.TextSecondary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Vc.SurfaceHigh) {
                    if (rec.isWav) {
                        MenuItem("Enhance with current effects", Icons.Rounded.AutoFixHigh) { menu = false; actions.onEnhance(rec) }
                        MenuItem("Export as M4A (AAC)", Icons.Rounded.MusicNote) { menu = false; actions.onExportM4a(rec) }
                    }
                    MenuItem("Save to Music folder", Icons.Rounded.SaveAlt) { menu = false; actions.onSaveToMusic(rec) }
                    MenuItem("Share", Icons.Rounded.Share) { menu = false; actions.onShare(rec) }
                    MenuItem("Rename", Icons.Rounded.Edit) { menu = false; dialogs.renaming = rec }
                    MenuItem("Delete", Icons.Rounded.Delete) { menu = false; dialogs.deleting = rec }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick, leadingIcon = { Icon(icon, null) })
}

/** Rounded dark card with a hairline border, the base surface of the design. */
@Composable
fun StudioCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Vc.Surface,
        border = BorderStroke(1.dp, Vc.Border),
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}

/** Icon-over-label cell used in the home mode strip and live stats. */
@Composable
fun StripCell(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingLabel: (@Composable () -> Unit)? = null,
    top: @Composable () -> Unit,
) {
    val tint = if (selected) Vc.Red else Vc.TextPrimary
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (selected) Vc.RedSoft.copy(alpha = 0.6f) else Color.Transparent)
            .padding(vertical = 12.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) { top() }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 14.sp,
                softWrap = false,
                color = if (selected) Vc.Red else Vc.TextSecondary.let { lerp(it, tint, 0.5f) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailingLabel?.invoke()
        }
    }
}
