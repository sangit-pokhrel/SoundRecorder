package com.vocalis.recorder.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vocalis.recorder.ui.theme.MeterAmber
import com.vocalis.recorder.ui.theme.MeterGreen
import com.vocalis.recorder.ui.theme.MeterRed
import kotlin.math.max
import kotlin.math.sqrt

/** Horizontal peak meter from -60 to 0 dBFS with a clip lamp. */
@Composable
fun LevelMeter(peakDb: Float, clipped: Boolean, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(((peakDb + 60f) / 60f).coerceIn(0f, 1f), tween(60), label = "meter")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))) {
            drawRect(track)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to MeterGreen, 0.7f to MeterGreen, 0.85f to MeterAmber, 1f to MeterRed,
                    endX = size.width,
                ),
                size = Size(size.width * fraction, size.height),
            )
            for (db in -54..-6 step 6) {
                val x = size.width * (db + 60) / 60f
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1.5f)
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (clipped) MeterRed else track)
                .semantics { contentDescription = if (clipped) "Clipping" else "No clipping" },
        )
    }
}

/** Static waveform of a file with playback progress, markers and tap/drag to seek. */
@Composable
fun FileWaveform(
    peaks: FloatArray?,
    progress: Float,
    markers: List<Float>,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.tertiary
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> seek((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val mid = size.height / 2
        val data = peaks
        if (data == null || data.isEmpty()) {
            drawLine(unplayed, Offset(0f, mid), Offset(size.width, mid), 2f)
        } else {
            val slot = size.width / data.size
            for (i in data.indices) {
                val x = i * slot + slot / 2
                val h = max(2f, sqrt(data[i]) * size.height * 0.95f)
                drawLine(
                    if (x / size.width <= progress) played else unplayed,
                    Offset(x, mid - h / 2), Offset(x, mid + h / 2),
                    strokeWidth = max(1f, slot * 0.6f), cap = StrokeCap.Round,
                )
            }
        }
        for (m in markers) {
            val x = m * size.width
            drawRoundRect(markerColor, Offset(x - 1.5f, 0f), Size(3f, size.height), CornerRadius(2f))
        }
        val px = progress * size.width
        drawLine(played, Offset(px, 0f), Offset(px, size.height), 3f)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
    )
}

/** `mm:ss`, or `h:mm:ss` past an hour; optionally with tenths (`mm:ss.t`) for the live timer. */
fun formatDuration(ms: Long, withTenths: Boolean = false): String {
    val safe = ms.coerceAtLeast(0)
    val totalSec = safe / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    return if (withTenths) "$base.${(safe % 1000) / 100}" else base
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    else -> "%d KB".format(bytes / 1000)
}
