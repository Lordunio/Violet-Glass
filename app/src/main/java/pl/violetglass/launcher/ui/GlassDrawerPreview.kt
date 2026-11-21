package pl.violetglass.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import pl.violetglass.launcher.R
import pl.violetglass.launcher.apps.AppsRepository
import pl.violetglass.launcher.apps.LauncherApp
import pl.violetglass.launcher.settings.*

@Composable
internal fun GlassDrawerPreview(apps: List<LauncherApp>, repository: AppsRepository, revision: Long,
    onCancel: () -> Unit, onSave: (Int) -> Unit) {
    val prefs = LocalLauncherPreferences.current
    val original = rememberSaveable { prefs.glass.transparency }
    var value by rememberSaveable { mutableIntStateOf(original) }
    val glass = prefs.glass.copy(transparency = value)
    val samples = remember(apps) {
        listOf(apps.firstOrNull { it.label.length in 2..8 && ' ' !in it.label },
            apps.firstOrNull { it.label.trim().contains(' ') })
    }
    val previewPrefs = prefs.copy(glass = glass)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("glassPreview"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompositionLocalProvider(LocalLauncherPreferences provides previewPrefs,
            LocalGlassPolicy provides resolveGlass(glass, LocalGlassEnvironment.current)) {
            GlassSurface(GlassTarget.DRAWER, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.all_apps), style = MaterialTheme.typography.headlineSmall.copy(shadow = drawerTextShadow()))
                    GlassSurface(GlassTarget.SEARCH, Modifier.fillMaxWidth()) {
                        DrawerSearchHint(Modifier.padding(16.dp))
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cell = (maxWidth / prefs.drawerColumns).coerceAtLeast(1.dp)
                        Row {
                            samples.forEachIndexed { index, app ->
                                val name = app?.label ?: stringResource(if (index == 0) R.string.glass_sample_short else R.string.glass_sample_long)
                                val display = if (index == 1) name.trim().replaceFirst(' ', '\n') else name
                                Column(Modifier.width(cell).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    AppIcon(app, name, repository, revision, minOf(prefs.iconSize.dp, cell))
                                    Spacer(Modifier.height(5.dp))
                                    val outlined = drawerLabelsOutlined(previewPrefs)
                                    DrawerAppLabel(display, Modifier.fillMaxWidth(), drawerLabelStyle(previewPrefs, outlined),
                                        outlined, maxLines = 2, singleWordSize = null)
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("${stringResource(R.string.glass_transparency)}: $value")
                Slider(value.toFloat(), { value = it.roundToInt() }, valueRange = 0f..100f,
                    modifier = Modifier.testTag("previewSlider"))
                if (!glass.enabled || GlassTarget.DRAWER in glass.disabledTargets)
                    Text(stringResource(R.string.preview_glass_disabled), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    TextButton(enabled = value != original, onClick = { value = original }, modifier = Modifier.testTag("previewUndo")) {
                        Text(stringResource(R.string.glass_undo))
                    }
                    Button(onClick = { onSave(value) }, modifier = Modifier.testTag("previewSave")) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}
