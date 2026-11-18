package pl.violetglass.launcher.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.violetglass.launcher.R
import pl.violetglass.launcher.BuildConfig
import pl.violetglass.launcher.settings.*

@Composable
fun PanelControls(ha: Boolean, discover: Boolean, onHa: (Boolean) -> Unit, onDiscover: (Boolean) -> Unit) {
    if(BuildConfig.DISCOVER_AVAILABLE) Text(stringResource(R.string.panel_order), style = MaterialTheme.typography.bodyMedium)
    Toggle("Home Assistant", ha, "panelHaEnabled", onHa)
    if(BuildConfig.DISCOVER_AVAILABLE) Toggle("Google Discover", discover, "panelDiscoverEnabled", onDiscover)
}
