package com.newsadda.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * Newsroom palette: neutral canvas, white cards, one brand red that also
 * means "breaking". Label colours are muted so the headline stays the hero.
 */
@Immutable
data class NewsColors(
    val canvas: Color,
    val card: Color,
    val breaking: Color,
    val onBreaking: Color,
    val breakingDeep: Color,
    val caution: Color,
    val cautionBg: Color,
    val allegation: Color,
    val allegationBg: Color,
    val multi: Color,
    val multiBg: Color,
    val official: Color,
    val officialBg: Color,
    val muted: Color,
    val faint: Color,
    val divider: Color,
    val newDot: Color,
    val live: Color,
    val iconRing: Color,
)

private val LightNews = NewsColors(
    canvas = Color(0xFFF2F1EE), card = Color(0xFFFFFFFF),
    breaking = Color(0xFFC0121C), onBreaking = Color.White, breakingDeep = Color(0xFF8E0C14),
    caution = Color(0xFF9A5B00), cautionBg = Color(0xFFFFF4E0),
    allegation = Color(0xFF7A2BB5), allegationBg = Color(0xFFF4EAFB),
    multi = Color(0xFF17703F), multiBg = Color(0xFFE6F4EC),
    official = Color(0xFF1D4FBF), officialBg = Color(0xFFE8EEFC),
    muted = Color(0xFF66625D), faint = Color(0xFF8D8882), divider = Color(0xFFE6E3DE),
    newDot = Color(0xFF2563EB), live = Color(0xFFE11D2A), iconRing = Color(0xFFFFFFFF),
)
private val DarkNews = NewsColors(
    canvas = Color(0xFF0E0E0F), card = Color(0xFF1A1A1C),
    breaking = Color(0xFFE8343E), onBreaking = Color.White, breakingDeep = Color(0xFF7A0E14),
    caution = Color(0xFFF2B54A), cautionBg = Color(0xFF33270F),
    allegation = Color(0xFFCF9BF2), allegationBg = Color(0xFF2B1E36),
    multi = Color(0xFF6FD39C), multiBg = Color(0xFF13291D),
    official = Color(0xFF8DB1FF), officialBg = Color(0xFF17213A),
    muted = Color(0xFFA6A19B), faint = Color(0xFF7D7974), divider = Color(0xFF2A2A2D),
    newDot = Color(0xFF6EA8FF), live = Color(0xFFFF4D57), iconRing = Color(0xFF1A1A1C),
)

val LocalNewsColors = staticCompositionLocalOf { LightNews }

private val LightScheme = lightColorScheme(
    primary = Color(0xFFC0121C), onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE3E4), onPrimaryContainer = Color(0xFF5C060B),
    secondary = Color(0xFF2B2A28), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCE3E4), onSecondaryContainer = Color(0xFF5C060B),
    background = Color(0xFFF2F1EE), onBackground = Color(0xFF17171A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF17171A),
    surfaceVariant = Color(0xFFEDEBE7), onSurfaceVariant = Color(0xFF66625D),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFF7F6F3),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFDAD6D0), outlineVariant = Color(0xFFE6E3DE),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF5A63), onPrimary = Color(0xFF2A0003),
    primaryContainer = Color(0xFF4A1014), onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFFE6E2DD), onSecondary = Color(0xFF17171A),
    secondaryContainer = Color(0xFF4A1014), onSecondaryContainer = Color(0xFFFFDADB),
    background = Color(0xFF0E0E0F), onBackground = Color(0xFFEDEBE8),
    surface = Color(0xFF1A1A1C), onSurface = Color(0xFFEDEBE8),
    surfaceVariant = Color(0xFF242427), onSurfaceVariant = Color(0xFFA6A19B),
    surfaceContainerLow = Color(0xFF1A1A1C), surfaceContainer = Color(0xFF151517),
    surfaceContainerHigh = Color(0xFF222225),
    outline = Color(0xFF3A3A3E), outlineVariant = Color(0xFF2A2A2D),
)

private val Type = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp),
    titleMedium = TextStyle(fontSize = 17.5.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
)

@Composable
fun NewsAddaTheme(textScale: Float, hindi: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalNewsColors provides if (dark) DarkNews else LightNews,
        LocalStr provides Str(hindi),
        LocalDensity provides Density(density.density, density.fontScale * textScale),
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = Type, content = content)
    }
}
