package dev.pulsarconnect.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pulsarconnect.app.R

private val Ink = Color(0xFF0E1318)
private val SurfaceDim = Color(0xFF090D11)
private val SurfaceBright = Color(0xFF2D343B)
private val SurfaceLowest = Color(0xFF0B1015)
private val SurfaceLow = Color(0xFF141A20)
private val Surface = Color(0xFF1B2228)
private val SurfaceHigh = Color(0xFF242C33)
private val SurfaceHighest = Color(0xFF2F373F)
private val Electric = Color(0xFF4DE1FF)
private val OnElectric = Color(0xFF003642)
private val ElectricContainer = Color(0xFF004E5E)
private val OnElectricContainer = Color(0xFFB2EBFF)
private val Amber = Color(0xFFFFB300)
private val OnAmber = Color(0xFF2A1D00)
private val AmberContainer = Color(0xFF3D2E00)
private val OnAmberContainer = Color(0xFFFFDF9E)
private val Mist = Color(0xFFE1E2E5)
private val Muted = Color(0xFF93A2B0)
private val Outline = Color(0xFF899298)
private val OutlineVariant = Color(0xFF2A343D)
private val Danger = Color(0xFFFFB4AB)
private val OnDanger = Color(0xFF690005)
private val DangerContainer = Color(0xFF93000A)
private val OnDangerContainer = Color(0xFFFFDAD6)

private val PulsarColors = darkColorScheme(
    primary = Electric,
    onPrimary = OnElectric,
    primaryContainer = ElectricContainer,
    onPrimaryContainer = OnElectricContainer,
    secondary = Amber,
    onSecondary = OnAmber,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = OnAmberContainer,
    tertiary = OnElectricContainer,
    onTertiary = OnElectric,
    background = Ink,
    onBackground = Mist,
    surface = Ink,
    onSurface = Mist,
    surfaceVariant = SurfaceHighest,
    onSurfaceVariant = Muted,
    surfaceTint = Electric,
    surfaceDim = SurfaceDim,
    surfaceBright = SurfaceBright,
    surfaceContainerLowest = SurfaceLowest,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Danger,
    onError = OnDanger,
    errorContainer = DangerContainer,
    onErrorContainer = OnDangerContainer,
    inverseSurface = Mist,
    inverseOnSurface = Ink,
    inversePrimary = ElectricContainer,
)

private val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

private val PulsarTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val PulsarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PulsarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulsarColors,
        typography = PulsarTypography,
        shapes = PulsarShapes,
        content = content,
    )
}
