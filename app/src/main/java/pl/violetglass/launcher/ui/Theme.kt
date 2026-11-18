package pl.violetglass.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun VioletTheme(mode: pl.violetglass.launcher.settings.ThemeMode = pl.violetglass.launcher.settings.ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val colors = if (mode == pl.violetglass.launcher.settings.ThemeMode.DARK || (mode == pl.violetglass.launcher.settings.ThemeMode.SYSTEM && isSystemInDarkTheme())) darkColorScheme(
        primary = Color(0xFFD0B4FF), background = Color(0xFF171121),
        surface = Color(0xFF211A2D), surfaceContainer = Color(0xFF2C233B),
    ) else lightColorScheme(
        primary = Color(0xFF7041A6), background = Color(0xFFF9F5FF),
        surface = Color(0xFFFFFBFF), surfaceContainer = Color(0xFFEEE5F7),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

