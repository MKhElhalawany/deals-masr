package com.dealsmasr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.dealsmasr.app.data.ThemeMode

val Brand = Color(0xFFE4572E)
val BrandDark = Color(0xFFB83C17)
val Ink = Color(0xFF1B1F24)
val SheinBlack = Color(0xFF111111)
val Success = Color(0xFF15803D)

/** Badge colour per tier key. */
fun tierColor(key: String?): Color = when (key) {
    "mega" -> Color(0xFF6A1B9A)
    "super" -> Color(0xFFC2185B)
    "hot" -> Color(0xFFE4572E)
    "deal" -> Color(0xFF2E7D32)
    else -> Color(0xFF546E7A)
}

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Ink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E6E3),
    onSecondaryContainer = Ink,
    background = Color(0xFFF8F6F4),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EEEA),
    onSurfaceVariant = Color(0xFF5F5A55),
    outline = Color(0xFFD8D2CC),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF3A0B00),
    primaryContainer = BrandDark,
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFFE8E6E3),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF2E2B28),
    onSecondaryContainer = Color(0xFFE8E6E3),
    background = Color(0xFF14120F),
    onBackground = Color(0xFFEDE7E2),
    surface = Color(0xFF1D1A17),
    onSurface = Color(0xFFEDE7E2),
    surfaceVariant = Color(0xFF2A2622),
    onSurfaceVariant = Color(0xFFBDB5AD),
    outline = Color(0xFF4A443F),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp),
    )
}

@Composable
fun DealsTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Arabic-first: force RTL regardless of the device language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}
