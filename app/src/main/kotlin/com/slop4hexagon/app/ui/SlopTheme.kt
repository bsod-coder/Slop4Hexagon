package com.slop4hexagon.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.slop4hexagon.core.settings.ThemeMode

private val Seed = Color(0xFF7BD88F)
private val Ink = Color(0xFF0B0B0F)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF17603A),
    onPrimary = Color.White,
    secondary = Color(0xFF4C6358),
    tertiary = Color(0xFF3E626E),
    background = Color(0xFFF7FAF6),
    surface = Color.White,
    error = Color(0xFF8C1D18),
)

private val DarkScheme = darkColorScheme(
    primary = Seed,
    onPrimary = Ink,
    secondary = Color(0xFFAFC9BC),
    tertiary = Color(0xFFA6CFDF),
    background = Ink,
    surface = Color(0xFF131318),
    error = Color(0xFFFFB4AB),
)

/**
 * Material 3 theme with system dynamic color where supported (API 31+),
 * falling back to the seed palette otherwise. [themeMode] SYSTEM follows the
 * device setting.
 */
@Composable
fun SlopTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }.getOrElse { if (dark) DarkScheme else LightScheme }
    } else {
        if (dark) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
