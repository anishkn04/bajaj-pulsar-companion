package dev.pulsarconnect.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0B0D10)
private val Surface = Color(0xFF151A21)
private val SurfaceVariant = Color(0xFF1E2630)
private val Electric = Color(0xFF4CC2FF)
private val Amber = Color(0xFFFFB300)
private val Mist = Color(0xFFE6EDF3)
private val Muted = Color(0xFF9FB0C0)
private val Danger = Color(0xFFFF6B6B)

private val PulsarColors = darkColorScheme(
    primary = Electric,
    onPrimary = Color(0xFF00263A),
    secondary = Amber,
    onSecondary = Color(0xFF2A1D00),
    background = Ink,
    onBackground = Mist,
    surface = Surface,
    onSurface = Mist,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Muted,
    error = Danger,
    onError = Color(0xFF3A0000),
)

@Composable
fun PulsarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulsarColors,
        typography = Typography(),
        content = content,
    )
}
