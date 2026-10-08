package app.tally.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Tally {
    val Bg = Color(0xFF07070B)
    val Surface = Color(0xFF111118)
    val SurfaceHi = Color(0xFF1A1A24)
    val Stroke = Color(0x1AFFFFFF)
    val Text = Color(0xFFF6F5FB)
    val Muted = Color(0xFF8A8AA0)
    val Faint = Color(0xFF4A4A5C)

    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFFF3D9A)
    val Orange = Color(0xFFFF8A3D)
    val Mint = Color(0xFF34E5A6)
    val Red = Color(0xFFFF5470)

    val Brand = Brush.linearGradient(listOf(Violet, Pink, Orange))
    val HeroColors = listOf(Color(0xFF5B21B6), Color(0xFFDB2777), Color(0xFFF97316))
}

private val Sans = FontFamily.SansSerif

private val TallyTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 48.sp, letterSpacing = (-1.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

@Composable
fun TallyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tally.Pink,
            onPrimary = Color.White,
            secondary = Tally.Violet,
            background = Tally.Bg,
            onBackground = Tally.Text,
            surface = Tally.Surface,
            onSurface = Tally.Text,
            onSurfaceVariant = Tally.Muted,
            surfaceVariant = Tally.SurfaceHi,
            surfaceContainer = Tally.Surface,
            surfaceContainerHigh = Tally.SurfaceHi,
            surfaceContainerHighest = Tally.SurfaceHi,
            surfaceContainerLow = Tally.Surface,
            outline = Tally.Faint,
            outlineVariant = Tally.Stroke,
            error = Tally.Red,
        ),
        typography = TallyTypography,
        content = content,
    )
}
