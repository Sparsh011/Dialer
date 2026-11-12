package com.sparsh.dialer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.graphics.ColorUtils
import com.sparsh.dialer.data.Prefs
import java.util.Locale

/** Appwrite brand palette ("Pink" + the neutral greys of the Pink design system). */
object Brand {
    val Pink = Color(0xFFFD366E)
    val PinkDeep = Color(0xFFC81D5A)
    val PinkSoft = Color(0xFFFF7A9F)
    val Mint = Color(0xFF85DBD8)
    val Orange = Color(0xFFFE9567)
    val Purple = Color(0xFF7C67FE)
    val Green = Color(0xFF10B981)
    val Red = Color(0xFFFF453A)

    val N0 = Color(0xFFFFFFFF)
    val N5 = Color(0xFFFAFAFB)
    val N10 = Color(0xFFF4F4F7)
    val N15 = Color(0xFFEDEDF0)
    val N20 = Color(0xFFE4E4E7)
    val N30 = Color(0xFFD8D8DB)
    val N50 = Color(0xFFADADB0)
    val N60 = Color(0xFF97979B)
    val N70 = Color(0xFF6C6C71)
    val N80 = Color(0xFF56565C)
    val N85 = Color(0xFF414146)
    val N90 = Color(0xFF2D2D31)
    val N95 = Color(0xFF1D1D21)
    val N97 = Color(0xFF19191C)
    val N98 = Color(0xFF131315)
    val N100 = Color(0xFF000000)
}

val AccentPresets = mapOf(
    "Pink" to Prefs.DEFAULT_ACCENT,
    "Jade" to 0xFF42D6A4.toInt(),
    "Sapphire" to 0xFF6EA8FF.toInt(),
    "Amethyst" to 0xFFBB9AF7.toInt(),
    "Amber" to 0xFFFFC36B.toInt(),
    "Teal" to 0xFF45D5D1.toInt(),
    "Copper" to 0xFFF69B77.toInt(),
)

fun accentHex(accent: Int): String = String.format(Locale.ROOT, "#%06X", accent and 0xFFFFFF)

fun parseAccent(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return hex.toInt(16) or 0xFF000000.toInt()
}

private fun readableAccent(accent: Int, background: Int, toward: Int): Int {
    if (ColorUtils.calculateContrast(accent, background) >= 4.5) return accent
    var low = 0f
    var high = 1f
    repeat(8) {
        val amount = (low + high) / 2f
        if (ColorUtils.calculateContrast(ColorUtils.blendARGB(accent, toward, amount), background) >= 4.5) {
            high = amount
        } else {
            low = amount
        }
    }
    return ColorUtils.blendARGB(accent, toward, high)
}

@Immutable
data class ExtraColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val missed: Color,
    /** Raised surface: cards, sheets, the expanded row. */
    val card: Color,
    /** 1px borders and dividers — the main source of structure in this UI. */
    val hairline: Color,
    /** Quiet fill for keys, search fields and icon buttons. */
    val fill: Color,
    /** Tertiary text. */
    val subtle: Color,
    val isDark: Boolean,
)

val LocalExtra = staticCompositionLocalOf {
    ExtraColors(Brand.Green, Color.White, Color(0xFFD8F5EA), Brand.Red, Color.White, Brand.N15, Brand.N10, Brand.N60, false)
}

private val Light = lightColorScheme(
    primary = Brand.Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3EB),
    onPrimaryContainer = Color(0xFF7A0F33),
    inversePrimary = Brand.PinkSoft,
    secondary = Brand.N80,
    onSecondary = Color.White,
    secondaryContainer = Brand.N15,
    onSecondaryContainer = Brand.N97,
    tertiary = Color(0xFF1F8F8B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5F3F1),
    onTertiaryContainer = Color(0xFF0B3B39),
    background = Color(0xFFF7F7F8),
    onBackground = Brand.N97,
    surface = Color(0xFFF7F7F8),
    onSurface = Brand.N97,
    surfaceVariant = Brand.N15,
    onSurfaceVariant = Brand.N70,
    surfaceTint = Color.Transparent,
    inverseSurface = Brand.N95,
    inverseOnSurface = Brand.N15,
    error = Color(0xFFE5294B),
    onError = Color.White,
    errorContainer = Color(0xFFFFE1E3),
    onErrorContainer = Color(0xFF7A0F1B),
    outline = Brand.N60,
    outlineVariant = Brand.N20,
    scrim = Color.Black,
    surfaceBright = Brand.N0,
    surfaceDim = Brand.N20,
    surfaceContainerLowest = Brand.N0,
    surfaceContainerLow = Brand.N0,
    surfaceContainer = Brand.N0,
    surfaceContainerHigh = Brand.N15,
    surfaceContainerHighest = Brand.N20,
)

private val Dark = darkColorScheme(
    primary = Brand.Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3D1323),
    onPrimaryContainer = Color(0xFFFFB5C9),
    inversePrimary = Brand.PinkDeep,
    secondary = Brand.N50,
    onSecondary = Brand.N97,
    secondaryContainer = Brand.N90,
    onSecondaryContainer = Brand.N15,
    tertiary = Brand.Mint,
    onTertiary = Color(0xFF0B3B39),
    tertiaryContainer = Color(0xFF123F3D),
    onTertiaryContainer = Color(0xFFB9EFEC),
    background = Brand.N100,
    onBackground = Brand.N15,
    surface = Brand.N100,
    onSurface = Brand.N15,
    surfaceVariant = Brand.N95,
    onSurfaceVariant = Color(0xFFA0A0A6),
    surfaceTint = Color.Transparent,
    inverseSurface = Brand.N15,
    inverseOnSurface = Brand.N97,
    error = Color(0xFFFF5C6C),
    onError = Color.White,
    errorContainer = Color(0xFF45141B),
    onErrorContainer = Color(0xFFFFB3BA),
    outline = Brand.N80,
    outlineVariant = Brand.N90,
    scrim = Color.Black,
    surfaceBright = Brand.N90,
    surfaceDim = Brand.N100,
    surfaceContainerLowest = Brand.N100,
    surfaceContainerLow = Color(0xFF0C0C0E),
    surfaceContainer = Color(0xFF101012),
    surfaceContainerHigh = Color(0xFF161619),
    surfaceContainerHighest = Color(0xFF222226),
)

private val LightExtra = ExtraColors(
    success = Color(0xFF0C9B6B), onSuccess = Color.White, successContainer = Color(0xFFE2F6EE),
    missed = Color(0xFFE0284A), card = Brand.N0, hairline = Color(0xFFE9E9EC), fill = Color(0xFFEFEFF2),
    subtle = Color(0xFF8E8E93), isDark = false,
)
private val DarkExtra = ExtraColors(
    success = Color(0xFF34D8A0), onSuccess = Color(0xFF00271A), successContainer = Color(0xFF0C2A20),
    missed = Color(0xFFFF6B79), card = Color(0xFF101012), hairline = Color(0xFF232327), fill = Color(0xFF161619),
    subtle = Color(0xFF77777D), isDark = true,
)

private val base = Typography()
private val AppType = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.035).em),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.035).em),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.035).em),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.03).em),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.03).em),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = (-0.015).em),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.5.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    // Material 3 interpolates bodyLarge and bodySmall for floating field labels.
    bodyLarge = base.bodyLarge.copy(letterSpacing = (-0.005f * base.bodyLarge.fontSize.value).sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.09.em),
)

val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun DialerTheme(
    themeMode: Int = 0,
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val accent by Prefs.accent.collectAsState()
    val scheme = remember(dark, accent) {
        val base = if (dark) Dark else Light
        val primary = readableAccent(accent, base.background.toArgb(), base.onBackground.toArgb())
        val container = ColorUtils.blendARGB(base.surface.toArgb(), primary, if (dark) 0.24f else 0.16f)
        val onPrimary = if (ColorUtils.calculateContrast(android.graphics.Color.BLACK, primary) >= 4.5) Color.Black else Color.White
        base.copy(
            primary = Color(primary),
            onPrimary = onPrimary,
            primaryContainer = Color(container),
            onPrimaryContainer = Color(readableAccent(primary, container, base.onBackground.toArgb())),
            inversePrimary = Color(readableAccent(accent, base.inverseSurface.toArgb(), base.inverseOnSurface.toArgb())),
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppType,
            shapes = AppShapes,
            content = content,
        )
    }
}
