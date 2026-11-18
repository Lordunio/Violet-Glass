package pl.violetglass.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import pl.violetglass.launcher.R

@Composable
fun DesktopOptions(onDismiss: () -> Unit, onSettings: () -> Unit, onLayout: () -> Unit, onWidgets: () -> Unit,
    onWallpaper: () -> Unit, onAddPage: () -> Unit, canAddPage: Boolean, onDefault: () -> Unit, isDefault: Boolean) {
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.desktop_options)) }, text = {
        Column {
            TextButton(onClick = onWallpaper, modifier = Modifier.testTag("desktopWallpaper")) { Text(stringResource(R.string.wallpaper)) }
            TextButton(onClick = onWidgets, modifier = Modifier.testTag("desktopWidgets")) { Text(stringResource(R.string.widgets)) }
            TextButton(onClick = onSettings, modifier = Modifier.testTag("desktopSettings")) { Text(stringResource(R.string.launcher_settings)) }
            TextButton(onClick = onLayout, modifier = Modifier.testTag("desktopGrid")) { Text(stringResource(R.string.layout_options)) }
            TextButton(onClick = onAddPage, enabled = canAddPage, modifier = Modifier.testTag("desktopAddPage")) { Text(stringResource(R.string.add_page)) }
            if (!isDefault) TextButton(onClick = onDefault) { Text(stringResource(R.string.default_launcher)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
