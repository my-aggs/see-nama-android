package com.seenama.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.seenama.tv.R

object Palette {
    val Background = Color(0xFF141414)
    val Surface = Color(0xFF1F1F1F)
    val SurfaceHigh = Color(0xFF2A2A2A)
    val Red = Color(0xFFE50914)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF808080)
    val Match = Color(0xFF46D369)
    val Imdb = Color(0xFFF5C518)
}

/** Persian-capable font, used for subtitles only. */
val SubtitleFont = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_black, FontWeight.Black),
)

private fun style(size: Int, weight: FontWeight, line: Int = (size * 1.35).toInt()) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

private val AppTypography = Typography(
    displayLarge = style(48, FontWeight.Black),
    displayMedium = style(40, FontWeight.Black),
    displaySmall = style(32, FontWeight.Bold),
    headlineLarge = style(28, FontWeight.Bold),
    headlineMedium = style(24, FontWeight.Bold),
    headlineSmall = style(20, FontWeight.Bold),
    titleLarge = style(20, FontWeight.Bold),
    titleMedium = style(16, FontWeight.Medium),
    titleSmall = style(14, FontWeight.Medium),
    bodyLarge = style(16, FontWeight.Normal),
    bodyMedium = style(14, FontWeight.Normal),
    bodySmall = style(12, FontWeight.Normal),
    labelLarge = style(14, FontWeight.Bold),
    labelMedium = style(12, FontWeight.Medium),
    labelSmall = style(11, FontWeight.Medium),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.TextPrimary,
            onPrimary = Color.Black,
            background = Palette.Background,
            surface = Palette.Surface,
            onSurface = Palette.TextPrimary,
            onSurfaceVariant = Palette.TextSecondary,
            border = Palette.TextPrimary,
        ),
        typography = AppTypography,
        content = content,
    )
}
