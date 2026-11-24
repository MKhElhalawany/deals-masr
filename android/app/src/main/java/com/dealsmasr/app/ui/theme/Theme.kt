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

// Visual identity "عروض مصر": Egyptian blue (lapis lazuli) + pharaonic gold, papyrus-light / night-blue dark.
val Brand = Color(0xFF12357A)        // lapis
val BrandDark = Color(0xFF0A1F4A)
val Gold = Color(0xFFC9971C)
val GoldLight = Color(0xFFE6C463)
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
    primaryContainer = Color(0xFFDCE4F7),
    onPrimaryContainer = BrandDark,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6EAC8),
    onSecondaryContainer = Color(0xFF3B2B00),
    tertiary = Coral,
    background = Color(0xFFF7F3EA),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFE9DC),
    onSurfaceVariant = Color(0xFF5C5547),
    outline = Color(0xFFD6CDB8),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = GoldLight,
    onPrimary = Color(0xFF2A1E00),
    primaryContainer = Color(0xFF3A2F0B),
    onPrimaryContainer = Color(0xFFF6EAC8),
    secondary = Color(0xFF9DB7EE),
    onSecondary = BrandDark,
    secondaryContainer = Color(0xFF1C2E57),
    onSecondaryContainer = Color(0xFFDCE4F7),
    tertiary = Color(0xFFFF8A65),
    background = Color(0xFF0A1324),
    onBackground = Color(0xFFEEF1F7),
    surface = Color(0xFF101C33),
    onSurface = Color(0xFFEEF1F7),
    surfaceVariant = Color(0xFF18264A),
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
