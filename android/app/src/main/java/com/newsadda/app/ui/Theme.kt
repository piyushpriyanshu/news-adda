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

/** Semantic colours for labels; kept few and muted. */
@Immutable
data class NewsColors(
    val breaking: Color,
    val onBreaking: Color,
    val caution: Color,
    val multi: Color,
    val allegation: Color,
    val muted: Color,
    val divider: Color,
    val newDot: Color,
)

private val LightNews = NewsColors(
    breaking = Color(0xFFA4161A), onBreaking = Color.White,
    caution = Color(0xFF8A5A00), multi = Color(0xFF2E5E4E), allegation = Color(0xFF7A2E8C),
    muted = Color(0xFF5E5A55), divider = Color(0xFFE4DFD8), newDot = Color(0xFF1F5FBF),
)
private val DarkNews = NewsColors(
    breaking = Color(0xFFE5484D), onBreaking = Color.White,
    caution = Color(0xFFE0A93B), multi = Color(0xFF6FC3A5), allegation = Color(0xFFC79BD6),
    muted = Color(0xFFA8A29B), divider = Color(0xFF2E2C2A), newDot = Color(0xFF6EA8FF),
)

val LocalNewsColors = staticCompositionLocalOf { LightNews }

private val LightScheme = lightColorScheme(
    primary = Color(0xFFA4161A), onPrimary = Color.White,
    secondary = Color(0xFF3D3A36), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2DEDA), onSecondaryContainer = Color(0xFF5A0F12),
    background = Color(0xFFFAF8F5), onBackground = Color(0xFF1B1A19),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1B1A19),
    surfaceVariant = Color(0xFFF1EDE7), onSurfaceVariant = Color(0xFF5E5A55),
    outline = Color(0xFFD6D0C8),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE5484D), onPrimary = Color.White,
    secondary = Color(0xFFD9D4CE), onSecondary = Color(0xFF1B1A19),
    secondaryContainer = Color(0xFF4A2224), onSecondaryContainer = Color(0xFFF6DADA),
    background = Color(0xFF141414), onBackground = Color(0xFFECE8E3),
    surface = Color(0xFF1C1C1C), onSurface = Color(0xFFECE8E3),
    surfaceVariant = Color(0xFF262524), onSurfaceVariant = Color(0xFFA8A29B),
    outline = Color(0xFF3A3836),
)

private val Type = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
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
