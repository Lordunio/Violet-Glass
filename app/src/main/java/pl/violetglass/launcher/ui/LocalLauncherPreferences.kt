package pl.violetglass.launcher.ui

import androidx.compose.runtime.staticCompositionLocalOf
import pl.violetglass.launcher.settings.LauncherPreferences

val LocalLauncherPreferences = staticCompositionLocalOf { LauncherPreferences() }
