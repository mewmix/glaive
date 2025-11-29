package com.mewmix.glaive.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mewmix.glaive.core.DebugLogger

@Immutable
data class GlaiveColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val error: Color
)

@Immutable
data class GlaiveShapes(
    val cornerRadius: Dp,
    val borderWidth: Dp
)

@Immutable
data class GlaiveTypography(
    val fontFamily: FontFamily,
    val name: String
)

@Immutable
data class ThemeConfig(
    val colors: GlaiveColors,
    val shapes: GlaiveShapes,
    val typography: GlaiveTypography
)

object ThemeDefaults {
    val Colors = GlaiveColors(
        background = Color(0xFF0A0A0A), // DeepSpace
        surface = Color(0xFF181818),    // SurfaceGray
        text = Color(0xFFEEEEEE),       // SoftWhite
        accent = Color(0xFF00E676),     // NeonGreen
        error = Color(0xFFD32F2F)       // DangerRed
    )
    val Shapes = GlaiveShapes(
        cornerRadius = 12.dp,
        borderWidth = 1.dp
    )
    val Typography = GlaiveTypography(
        fontFamily = FontFamily.Monospace,
        name = "Monospace"
    )
}

val LocalGlaiveTheme = staticCompositionLocalOf {
    ThemeConfig(ThemeDefaults.Colors, ThemeDefaults.Shapes, ThemeDefaults.Typography)
}

object ThemeManager {
    private const val PREFS_NAME = "glaive_theme"

    // Keys
    private const val KEY_BG = "color_bg"
    private const val KEY_SURFACE = "color_surface"
    private const val KEY_TEXT = "color_text"
    private const val KEY_ACCENT = "color_accent"
    private const val KEY_ERROR = "color_error"
    private const val KEY_RADIUS = "shape_radius"
    private const val KEY_BORDER = "shape_border"
    private const val KEY_FONT = "type_font"

    fun loadTheme(context: Context): ThemeConfig {
        DebugLogger.log("ThemeManager: Loading theme")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val colors = GlaiveColors(
            background = Color(prefs.getInt(KEY_BG, ThemeDefaults.Colors.background.toArgb())),
            surface = Color(prefs.getInt(KEY_SURFACE, ThemeDefaults.Colors.surface.toArgb())),
            text = Color(prefs.getInt(KEY_TEXT, ThemeDefaults.Colors.text.toArgb())),
            accent = Color(prefs.getInt(KEY_ACCENT, ThemeDefaults.Colors.accent.toArgb())),
            error = Color(prefs.getInt(KEY_ERROR, ThemeDefaults.Colors.error.toArgb()))
        )

        val shapes = GlaiveShapes(
            cornerRadius = prefs.getFloat(KEY_RADIUS, 12f).dp,
            borderWidth = prefs.getFloat(KEY_BORDER, 1f).dp
        )

        val fontName = prefs.getString(KEY_FONT, "Monospace") ?: "Monospace"
        DebugLogger.log("ThemeManager: Loaded font '$fontName'")
        val fontFamily = GoogleFontsProvider.getFontFamily(fontName)

        return ThemeConfig(colors, shapes, GlaiveTypography(fontFamily, fontName))
    }

    fun saveTheme(context: Context, config: ThemeConfig) {
        DebugLogger.log("ThemeManager: Saving theme with font '${config.typography.name}'")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_BG, config.colors.background.toArgb())
            putInt(KEY_SURFACE, config.colors.surface.toArgb())
            putInt(KEY_TEXT, config.colors.text.toArgb())
            putInt(KEY_ACCENT, config.colors.accent.toArgb())
            putInt(KEY_ERROR, config.colors.error.toArgb())
            putFloat(KEY_RADIUS, config.shapes.cornerRadius.value)
            putFloat(KEY_BORDER, config.shapes.borderWidth.value)
            putString(KEY_FONT, config.typography.name)
        }.apply()
    }

    fun preRenderFont(fontName: String) {
        DebugLogger.log("ThemeManager: Pre-rendering font '$fontName' to verify availability")
        try {
            // Attempt to resolve the font family. This doesn't guarantee visual rendering
            // but ensures the provider is queried and any internal caching logic is triggered.
            // If the font is invalid, GoogleFontsProvider might return a fallback,
            // but the logging inside it will confirm the request.
            val family = GoogleFontsProvider.getFontFamily(fontName)
            DebugLogger.log("ThemeManager: Pre-render completed for '$fontName'. Family: $family")
        } catch (e: Exception) {
            DebugLogger.log("ThemeManager: Failed to pre-render font '$fontName'")
            e.printStackTrace()
        }
    }
}

@Composable
fun GlaiveTheme(
    config: ThemeConfig,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = config.colors.accent,
        onPrimary = Color.Black,
        background = config.colors.background,
        onBackground = config.colors.text,
        surface = config.colors.surface,
        onSurface = config.colors.text,
        error = config.colors.error,
        onError = Color.White
    )

    CompositionLocalProvider(
        LocalGlaiveTheme provides config
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = createTypography(config.typography.fontFamily),
            content = {
                ProvideTextStyle(
                    value = TextStyle(
                        fontFamily = config.typography.fontFamily,
                        color = config.colors.text
                    ),
                    content = content
                )
            }
        )
    }
}

private fun createTypography(fontFamily: FontFamily): Typography {
    val default = Typography()
    return Typography(
        displayLarge = default.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = default.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = default.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = default.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = default.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = default.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = default.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = default.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = default.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = default.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = default.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = default.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = default.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = default.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = default.labelSmall.copy(fontFamily = fontFamily)
    )
}
