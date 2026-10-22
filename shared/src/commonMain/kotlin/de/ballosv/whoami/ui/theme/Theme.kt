package de.ballosv.whoami.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF14112B)
val Surface = Color(0xFF221D45)
val SurfaceHigh = Color(0xFF2E2860)
val Accent = Color(0xFFFFB300)
val TileA = Color(0xFF6C4BF4)
val TileB = Color(0xFF4A2FD0)
val Good = Color(0xFF3DDC84)
val Bad = Color(0xFFFF5370)
val Buzzer = Color(0xFFE53950)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF241A00),
    secondary = TileA,
    onSecondary = Color.White,
    tertiary = Good,
    background = Background,
    onBackground = Color(0xFFF4F1FF),
    surface = Background,
    onSurface = Color(0xFFF4F1FF),
    surfaceVariant = Surface,
    onSurfaceVariant = Color(0xFFA9A3D1),
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    surfaceContainerLow = Surface,
    error = Bad,
)

@Composable
fun WhoAmITheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
