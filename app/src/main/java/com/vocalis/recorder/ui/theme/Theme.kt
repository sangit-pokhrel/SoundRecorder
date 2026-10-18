package com.vocalis.recorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design tokens for the Vocalis "night studio" look. */
object Vc {
    val Background = Color(0xFF0C0E14)
    val Surface = Color(0xFF151821)
    val SurfaceHigh = Color(0xFF1C2029)
    val SurfaceHigher = Color(0xFF262A36)
    val Border = Color(0xFF242836)
    val Red = Color(0xFFFF3D50)
    val RedDeep = Color(0xFFE0243A)
    val RedSoft = Color(0xFF3A1A22)
    val TextPrimary = Color(0xFFF2F3F7)
    val TextSecondary = Color(0xFF9399A8)
    val TextMuted = Color(0xFF5E6475)
    val Lavender = Color(0xFFAEB1E0)
    val WaveIdle = Color(0xFF3E4357)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFF6C344)

    val RecordGradient = Brush.verticalGradient(listOf(Color(0xFFFF5566), RedDeep))
}

// Kept for components that colour meters.
val RecordRed = Vc.Red
val MeterGreen = Vc.Green
val MeterAmber = Vc.Amber
val MeterRed = Vc.Red

private val Scheme = darkColorScheme(
    primary = Vc.Red,
    onPrimary = Color.White,
    primaryContainer = Vc.RedSoft,
    onPrimaryContainer = Color(0xFFFFD9DC),
    secondary = Vc.Lavender,
    onSecondary = Color(0xFF1A1B2E),
    secondaryContainer = Vc.SurfaceHigher,
    onSecondaryContainer = Vc.TextPrimary,
    tertiary = Vc.Amber,
    background = Vc.Background,
    onBackground = Vc.TextPrimary,
    surface = Vc.Background,
    onSurface = Vc.TextPrimary,
    surfaceVariant = Vc.SurfaceHigh,
    onSurfaceVariant = Vc.TextSecondary,
    surfaceContainerLowest = Vc.Background,
    surfaceContainerLow = Vc.Surface,
    surfaceContainer = Vc.SurfaceHigh,
    surfaceContainerHigh = Vc.SurfaceHigh,
    surfaceContainerHighest = Vc.SurfaceHigher,
    outline = Color(0xFF3B4050),
    outlineVariant = Vc.Border,
    error = Color(0xFFFF6B6B),
)

private val base = Typography()
private val AppTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = (-1).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** Vocalis is designed dark-first, like a studio at night. */
@Composable
fun VocalisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
