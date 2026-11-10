package nl.arnoldkolkman.akonotulist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand: deep teal + warm coral record accent. */
val Teal = Color(0xFF0E5E6F)
val TealLight = Color(0xFF7FD1DE)
val Coral = Color(0xFFE5484D)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EAF3),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF4A6268),
    tertiary = Coral,
    background = Color(0xFFF7FAFB),
    surface = Color(0xFFF7FAFB),
)

private val Dark = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5A),
    onPrimaryContainer = Color(0xFFB8EAF3),
    secondary = Color(0xFFB1CBD1),
    tertiary = Color(0xFFFF8A80),
)

/** Distinct, colour-blind friendly speaker colours (Okabe–Ito inspired). */
val SpeakerColors = listOf(
    Color(0xFF0072B2), Color(0xFFD55E00), Color(0xFF009E73), Color(0xFFCC79A7),
    Color(0xFFE69F00), Color(0xFF56B4E9), Color(0xFF7A5195), Color(0xFF8C6D31),
)

/** Palette for categories (index stored per category). Distinct in light and dark mode. */
val CategoryColors = listOf(
    Color(0xFF0E7C86), Color(0xFF3F6AD8), Color(0xFF2E9E5B), Color(0xFFD9822B), Color(0xFF8E44AD),
    Color(0xFFE5A50A), Color(0xFFE5484D), Color(0xFF7A6A58), Color(0xFFD6336C), Color(0xFF5C7080),
)

fun categoryColor(index: Int): Color = CategoryColors[index.mod(CategoryColors.size)]

fun speakerColor(label: String?): Color {
    if (label.isNullOrEmpty()) return Color.Gray
    val idx = if (label.length == 1 && label[0] in 'A'..'Z') label[0] - 'A' else label.hashCode().mod(SpeakerColors.size)
    return SpeakerColors[idx % SpeakerColors.size]
}

@Composable
fun AkoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Brand colours on purpose (no dynamic colour): the app should look the same on every device.
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
