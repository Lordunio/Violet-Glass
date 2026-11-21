package pl.violetglass.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.violetglass.launcher.R
import pl.violetglass.launcher.settings.*

@Composable
fun glassTargetLabel(target: GlassTarget): String = stringResource(when (target) {
    GlassTarget.DOCK -> R.string.dock_label; GlassTarget.FOLDERS -> R.string.folders
    GlassTarget.DRAWER -> R.string.all_apps; GlassTarget.SEARCH -> R.string.glass_search
    GlassTarget.MENUS -> R.string.glass_menus; GlassTarget.SETTINGS -> R.string.personalization
    GlassTarget.PANELS -> R.string.glass_panels
})
fun glassModeLabel(mode: GlassMode): String = when(mode) { GlassMode.ULTRA -> "Ultra Glass"; GlassMode.CLASSIC -> "Classic Glass"; GlassMode.LITE -> "Lite" }

@Composable
fun GlassControls(settings: GlassSettings, onPreview: () -> Unit, update: ((GlassSettings) -> GlassSettings) -> Unit) {
    FilledTonalButton(onClick = onPreview, modifier = Modifier.testTag("glassPreviewOpen")) {
        Text(stringResource(R.string.glass_preview))
    }
    Toggle(stringResource(R.string.glass_enabled), settings.enabled, "glassEnabled") { update { g -> g.copy(enabled = it) } }
    Text(stringResource(R.string.glass_off_hint), style = MaterialTheme.typography.bodySmall)
    // Keep controls available while globally disabled; restoring Glass preserves every choice.
    Text(stringResource(R.string.glass_targets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    GlassTarget.entries.forEach { target ->
        Toggle(glassTargetLabel(target), target !in settings.disabledTargets, "glassTarget:${target.name}") { enabled ->
            update { g -> g.copy(disabledTargets = if (enabled) g.disabledTargets - target else g.disabledTargets + target) }
        }
    }
    SettingSlider(stringResource(R.string.glass_blur), settings.blur, 0..100, "glassBlur") { update { g -> g.copy(blur = it) } }
    SettingSlider(stringResource(R.string.glass_transparency), settings.transparency, 0..100, "glassTransparency") { update { g -> g.copy(transparency = it) } }
    SettingSlider(stringResource(R.string.glass_reflections), settings.reflections, 0..100, "glassReflections") { update { g -> g.copy(reflections = it) } }
    SettingSlider(stringResource(R.string.glass_animation), settings.animation, 0..100, "glassAnimation") { update { g -> g.copy(animation = it) } }
    SettingSlider(stringResource(R.string.glass_corners), settings.corners, 0..48, "glassCorners") { update { g -> g.copy(corners = it) } }
    Text(stringResource(R.string.glass_readability), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun PerformanceControls(settings: GlassSettings, update: ((GlassSettings) -> GlassSettings) -> Unit) {
    val policy = LocalGlassPolicy.current
    val status = if (!policy.enabled) stringResource(R.string.glass_disabled_status) else glassModeLabel(policy.mode)
    Text(stringResource(R.string.glass_active_mode, status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("glassEffectiveMode"))
    if (policy.enabled && policy.limit != GlassLimit.NONE) Text(stringResource(when(policy.limit) {
        GlassLimit.LOW_RAM -> R.string.glass_limit_memory; GlassLimit.BATTERY -> R.string.glass_limit_battery
        GlassLimit.HEAT -> R.string.glass_limit_heat; else -> R.string.glass_limit_hardware
    }), style = MaterialTheme.typography.bodySmall)
    GlassMode.entries.forEach { mode ->
        RadioRow(glassModeLabel(mode), settings.mode == mode, "glassMode:${mode.name}") { update { it.copy(mode = mode) } }
        Text(stringResource(when(mode) { GlassMode.ULTRA -> R.string.glass_ultra_hint; GlassMode.CLASSIC -> R.string.glass_classic_hint; GlassMode.LITE -> R.string.glass_lite_hint }), style = MaterialTheme.typography.bodySmall)
    }
    Toggle(stringResource(R.string.glass_automatic), settings.automatic, "glassAutomatic") { update { g -> g.copy(automatic = it) } }
    Text(stringResource(R.string.glass_automatic_hint), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.glass_motion_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
}
