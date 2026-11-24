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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dealsmasr.app.data.ThemeMode

// Visual identity "عروض مصر" (v2, premium): midnight blue + champagne gold on warm ivory; midnight-black dark mode.
val Brand = Color(0xFF0E1B3D)        // midnight
val BrandDark = Color(0xFF070D1F)
val Gold = Color(0xFFC8A45D)         // champagne
val GoldLight = Color(0xFFE8CF8F)
val Coral = Color(0xFFE4572E)        // discount seal
val Ink = Color(0xFF17202E)
val SheinBlack = Color(0xFF111111)
val Success = Color(0xFF1B8A4B)

/** Badge colour per tier key: ميجا = gold, سوبر = magenta, ساخن = coral, خصم = green. */
fun tierColor(key: String?): Color = when (key) {
    "mega" -> Color(0xFFB8860B)
    "super" -> Color(0xFFB0185E)
    "hot" -> Coral
    "deal" -> Color(0xFF2E7D32)
    else -> Color(0xFF546E7A)
}

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E8F3),
    onPrimaryContainer = BrandDark,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4EAD3),
    onSecondaryContainer = Color(0xFF3B2B00),
    tertiary = Coral,
    background = Color(0xFFFAF7F1),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1ECE2),
    onSurfaceVariant = Color(0xFF5C5547),
    outline = Color(0xFFDDD3BF),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = GoldLight,
    onPrimary = Color(0xFF2A1E00),
    primaryContainer = Color(0xFF3A3018),
    onPrimaryContainer = Color(0xFFF6EAC8),
    secondary = Color(0xFF9DB7EE),
    onSecondary = BrandDark,
    secondaryContainer = Color(0xFF1A2650),
    onSecondaryContainer = Color(0xFFDCE4F7),
    tertiary = Color(0xFFFF8A65),
    background = Color(0xFF070D1F),
    onBackground = Color(0xFFEEF1F7),
    surface = Color(0xFF0E1730),
    onSurface = Color(0xFFEEF1F7),
    surfaceVariant = Color(0xFF16213F),
    onSurfaceVariant = Color(0xFFB5BED2),
    outline = Color(0xFF2E3E63),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Black),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = androidx.compose.material3.Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun DealsTheme(mode: ThemeMode = ThemeMode.SYSTEM, language: String = "ar", content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Arabic-first: RTL regardless of the device language; English (chosen in Settings) is LTR.
    CompositionLocalProvider(
        LocalLayoutDirection provides if (language == "en") LayoutDirection.Ltr else LayoutDirection.Rtl,
        com.dealsmasr.app.ui.i18n.LocalLang provides language,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
