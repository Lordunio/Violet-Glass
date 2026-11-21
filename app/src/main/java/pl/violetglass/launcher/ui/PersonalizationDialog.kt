package pl.violetglass.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.violetglass.launcher.R
import pl.violetglass.launcher.apps.LauncherApp
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.gestures.SystemActions
import kotlin.math.roundToInt

private enum class Section { DESKTOP, ICONS, DOCK, FOLDERS, DRAWER, SCREENS, PET, WALLPAPER, GLASS, PERFORMANCE, GESTURES, WIDGETS, HA, DISCOVER, BACKUP, ABOUT }
@Composable
private fun sectionLabel(section: Section) = stringResource(when(section) {
    Section.PET -> R.string.pet_settings
    Section.WALLPAPER -> R.string.wallpaper_effect_settings
    Section.DESKTOP -> R.string.home; Section.ICONS -> R.string.icons; Section.DOCK -> R.string.dock_label
    Section.FOLDERS -> R.string.folders; Section.DRAWER -> R.string.all_apps; Section.GESTURES -> R.string.gestures; Section.WIDGETS -> R.string.widgets
    Section.GLASS -> R.string.glass_title; Section.PERFORMANCE -> R.string.performance
    Section.HA -> R.string.ha_settings
    Section.SCREENS -> R.string.side_panels; Section.DISCOVER -> R.string.discover_title
    Section.ABOUT -> R.string.about_title
    Section.BACKUP -> R.string.backup_title
})
@Composable
fun gestureLabel(gesture: DesktopGesture) = stringResource(when(gesture) {
    DesktopGesture.SWIPE_UP -> R.string.gesture_up; DesktopGesture.SWIPE_DOWN -> R.string.gesture_down
    DesktopGesture.DOUBLE_TAP -> R.string.gesture_double; DesktopGesture.LONG_PRESS -> R.string.gesture_long; DesktopGesture.TWO_FINGER_UP -> R.string.gesture_two
})
@Composable
fun actionLabel(action: GestureAction) = stringResource(when(action) {
    GestureAction.NONE -> R.string.action_none; GestureAction.APPS -> R.string.action_apps; GestureAction.SEARCH -> R.string.action_search
    GestureAction.SETTINGS -> R.string.action_settings; GestureAction.WIDGETS -> R.string.action_widgets; GestureAction.APP -> R.string.action_app
    GestureAction.SHORTCUT -> R.string.action_shortcut; GestureAction.WALLPAPER -> R.string.wallpaper; GestureAction.LOCK -> R.string.action_lock
})

@Composable
fun PersonalizationDialog(model: LauncherViewModel, apps: List<LauncherApp>, systemActions: SystemActions,
    onDismiss: () -> Unit, onGrid: () -> Unit, onWidgets: () -> Unit,
    initialSection: String? = null,
) {
    val prefs = LocalLauncherPreferences.current
    val pendingBundle by model.pendingWidget.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(Section.entries.find { it.name == initialSection }) }
    var hidden by remember { mutableStateOf(false) }
    var editingGesture by remember { mutableStateOf<DesktopGesture?>(null) }
    var pickingApp by remember { mutableStateOf<Pair<DesktopGesture, GestureAction>?>(null) }
    var pickingShortcut by remember { mutableStateOf<Pair<DesktopGesture, LauncherApp>?>(null) }
    var lockEnabled by remember { mutableStateOf(systemActions.isLockEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, systemActions) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) lockEnabled = systemActions.isLockEnabled() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(GlassTarget.SETTINGS, Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), backdrop = LocalWorkspaceBackdrop.current) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (section != null) TextButton(onClick = { section = null }, modifier = Modifier.testTag("settingsBack")) { Text("‹") }
                    Text(section?.let { sectionLabel(it) } ?: stringResource(R.string.launcher_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("closePersonalization")) { Text(stringResource(R.string.close)) }
                }
                if(section != Section.BACKUP) Text(stringResource(R.string.settings_live), style = MaterialTheme.typography.bodySmall)
                key(section) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        when(section) {
                            Section.PET -> pl.violetglass.launcher.pet.PetControls(androidx.lifecycle.viewmodel.compose.viewModel())
                            Section.WALLPAPER -> pl.violetglass.launcher.pet.WallpaperEffectControls(androidx.lifecycle.viewmodel.compose.viewModel())
                            null -> Section.entries.filter { pl.violetglass.launcher.BuildConfig.DISCOVER_AVAILABLE || it != Section.DISCOVER }.forEach { value ->
                                TextButton(onClick = { section = value }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("section:${value.name}")) {
                                    Text(sectionLabel(value), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Text("›")
                                }
                            }
                            Section.DESKTOP -> {
                                Toggle(stringResource(R.string.auto_add_new_apps), prefs.autoAddNewApps, "autoAddNewApps", model::setAutoAddNewApps)
                                Toggle(stringResource(R.string.auto_rotate), prefs.autoRotate, "autoRotate") { model.updatePreferences { p -> p.copy(autoRotate = it) } }
                                Toggle(stringResource(R.string.system_wallpaper), prefs.systemWallpaper, "systemWallpaper") { model.updatePreferences { p -> p.copy(systemWallpaper = it) } }
                                Toggle(stringResource(R.string.lock_layout), prefs.locked, "lockLayout") { model.updatePreferences { p -> p.copy(locked = it) } }
                                TextButton(onClick = { onDismiss(); onGrid() }) { Text(stringResource(R.string.layout_options)) }
                                TextButton(onClick = { model.editLayout { pl.violetglass.launcher.workspace.LayoutEngine.resize(it, 6, it.config.rows, 6) } }, enabled = !prefs.locked, modifier = Modifier.testTag("sixColumns")) { Text(stringResource(R.string.six_columns)) }
                                SettingSlider(stringResource(R.string.desktop_margin), prefs.margin, 0..24, "margin") { model.updatePreferences { p -> p.copy(margin = it) } }
                                SettingSlider(stringResource(R.string.icon_gap), prefs.gap, 0..16, "gap") { model.updatePreferences { p -> p.copy(gap = it) } }
                                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
                                ThemeMode.entries.forEach { mode ->
                                    RadioRow(stringResource(when(mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }),
                                        prefs.theme == mode, "theme:${mode.name}") { model.updatePreferences { it.copy(theme = mode) } }
                                }
                            }
                            Section.ICONS -> {
                                BadgeSettings(model)
                                HorizontalDivider()
                                IconPackSettings(model)
                                SettingSlider(stringResource(R.string.icon_size), prefs.iconSize, 28..72, "iconSize") { model.updatePreferences { p -> p.copy(iconSize = it) } }
                                SettingSlider(stringResource(R.string.label_size), prefs.labelSize, 10..18, "labelSize") { model.updatePreferences { p -> p.copy(labelSize = it) } }
                                Toggle(stringResource(R.string.icon_labels), prefs.labels, "iconLabels") { model.updatePreferences { p -> p.copy(labels = it) } }
                                Text(stringResource(R.string.label_color), style = MaterialTheme.typography.titleMedium)
                                LabelColor.entries.forEach { color ->
                                    RadioRow(stringResource(when(color) { LabelColor.AUTO -> R.string.color_auto; LabelColor.WHITE -> R.string.color_white; LabelColor.BLACK -> R.string.color_black; LabelColor.VIOLET -> R.string.color_violet }),
                                        prefs.labelColor == color, "labelColor:${color.name}") { model.updatePreferences { it.copy(labelColor = color) } }
                                }
                            }
                            Section.DOCK -> {
                                Toggle(stringResource(R.string.dock_visible), prefs.dockVisible, "dockVisible") { model.updatePreferences { p -> p.copy(dockVisible = it) } }
                                Toggle(stringResource(R.string.dock_background), prefs.dockBackground, "dockBackground") { model.updatePreferences { p -> p.copy(dockBackground = it) } }
                                SettingSlider(stringResource(R.string.dock_height), prefs.dockHeight, 72..140, "dockHeight") { model.updatePreferences { p -> p.copy(dockHeight = it) } }
                            }
                            Section.FOLDERS -> NumberRow(stringResource(R.string.folder_columns), prefs.folderColumns, 3..6, "folderColumns") { model.updatePreferences { p -> p.copy(folderColumns = it) } }
                            Section.DRAWER -> {
                                NumberRow(stringResource(R.string.drawer_columns), prefs.drawerColumns, 3..8, "drawerColumns") { model.updatePreferences { p -> p.copy(drawerColumns = it) } }
                                Toggle(stringResource(R.string.reverse_apps), prefs.reverseApps, "reverseApps") { model.updatePreferences { p -> p.copy(reverseApps = it) } }
                                TextButton(onClick = { hidden = true }, modifier = Modifier.testTag("hiddenApps")) { Text(stringResource(R.string.hide_apps)) }
                                Text(stringResource(R.string.hide_apps_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            Section.GESTURES -> {
                                Text(stringResource(R.string.pinch_hint), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.gesture_hint), style = MaterialTheme.typography.bodySmall)
                                DesktopGesture.entries.forEach { gesture ->
                                    val binding = prefs.gestures[gesture] ?: GestureBinding()
                                    TextButton(onClick = { editingGesture = gesture }, modifier = Modifier.fillMaxWidth().testTag("gesture:${gesture.name}")) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(gestureLabel(gesture))
                                            Text(actionLabel(binding.action) + (apps.find { it.key == binding.appKey }?.let { " · " + it.label } ?: ""), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                HorizontalDivider()
                                Text(stringResource(R.string.lock_explanation), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    if (lockEnabled) { systemActions.disableLock(); lockEnabled = systemActions.isLockEnabled() }
                                    else systemActions.enableLock()
                                }) { Text(stringResource(if (lockEnabled) R.string.disable_screen_lock else R.string.enable_screen_lock)) }
                            }
                            Section.WIDGETS -> {
                                Text(stringResource(R.string.widget_picker_hint))
                                TextButton(onClick = { onDismiss(); onWidgets() }, modifier = Modifier.testTag("addWidgetFromSettings")) { Text(stringResource(R.string.add_widget)) }
                                if (pendingBundle != null) TextButton(onClick = model::cancelWidget, enabled = model.pending()?.stage != "commit") { Text(stringResource(R.string.cancel_widget)) }
                            }
                            Section.GLASS -> GlassControls(prefs.glass) { change -> model.updatePreferences { p -> p.copy(glass = change(p.glass)) } }
                            Section.PERFORMANCE -> PerformanceControls(prefs.glass) { change -> model.updatePreferences { p -> p.copy(glass = change(p.glass)) } }
                            Section.HA -> {
                                HaSettingsControls(prefs.ha) { value -> model.updatePreferences { p -> p.copy(ha = value) } }
                                TextButton(onClick = model::refreshHa, modifier = Modifier.testTag("haRefresh")) { Text(stringResource(R.string.ha_refresh)) }
                            }
                            Section.SCREENS -> {
                                PanelControls(prefs.ha.enabled, prefs.discover.enabled,
                                    { enabled -> model.updatePreferences { p -> p.copy(ha = p.ha.copy(enabled = enabled)) } },
                                    { enabled -> model.updatePreferences { p -> p.copy(discover = p.discover.copy(enabled = enabled)) } })
                                Toggle(stringResource(R.string.pet_game_panel), prefs.petGameEnabled, "panelPetGameEnabled") {
                                    model.updatePreferences { p -> p.copy(petGameEnabled = it) }
                                }
                                Text(stringResource(R.string.pet_game_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.pet_game_help), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.pet_game_bonus_help), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.pet_game_lock_help), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.pet_game_pause_help), style = MaterialTheme.typography.bodyMedium)
                            }
                            Section.DISCOVER -> {
                                DiscoverControls(prefs.discover) { value -> model.updatePreferences { p -> p.copy(discover = value) } }
                                TextButton(onClick = model::refreshDiscover, modifier = Modifier.testTag("discoverReconnect")) { Text(stringResource(R.string.discover_reconnect)) }
                            }
                            Section.BACKUP -> BackupControls(model)
                            Section.ABOUT -> {
                                Text("Violet Glass Launcher", style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.about_version, pl.violetglass.launcher.BuildConfig.VERSION_NAME))
                                Text(stringResource(if (pl.violetglass.launcher.BuildConfig.DEBUG) R.string.about_debug else R.string.about_private))
                                Text(stringResource(R.string.about_android))
                                Text(stringResource(R.string.about_update))
                                Text(stringResource(R.string.about_return))
                            }
                        }
                    }
                }
            }
        }
    }
    if (hidden) AppSelectionDialog(apps, stringResource(R.string.hide_apps), { hidden = false }, selected = prefs.hiddenApps,
        onToggle = { app -> model.updatePreferences { p -> p.copy(hiddenApps = if (app.key in p.hiddenApps) p.hiddenApps - app.key else p.hiddenApps + app.key) } })
    editingGesture?.let { gesture ->
        GlassAlertDialog(onDismissRequest = { editingGesture = null }, title = { Text(gestureLabel(gesture)) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                GestureAction.entries.forEach { action ->
                    TextButton(onClick = {
                        editingGesture = null
                        if (action == GestureAction.APP || action == GestureAction.SHORTCUT) pickingApp = gesture to action
                        else model.updatePreferences { p -> p.copy(gestures = p.gestures + (gesture to GestureBinding(action))) }
                    }, modifier = Modifier.testTag("action:${action.name}")) { Text(actionLabel(action)) }
                }
            }
        }, confirmButton = { TextButton(onClick = { editingGesture = null }) { Text(stringResource(R.string.cancel)) } })
    }
    pickingApp?.let { (gesture, action) ->
        AppSelectionDialog(apps, stringResource(R.string.choose_app), { pickingApp = null }, onSelect = { app ->
            pickingApp = null
            if (action == GestureAction.SHORTCUT) pickingShortcut = gesture to app
            else model.updatePreferences { p -> p.copy(gestures = p.gestures + (gesture to GestureBinding(action, app.key))) }
        })
    }
    pickingShortcut?.let { (gesture, app) -> ShortcutSelectionDialog(model, app, { pickingShortcut = null }) { id ->
        model.updatePreferences { p -> p.copy(gestures = p.gestures + (gesture to GestureBinding(GestureAction.SHORTCUT, app.key, id))) }
        pickingShortcut = null
    } }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, key: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange, modifier = Modifier.testTag(key))
    }
}
@Composable
internal fun RadioRow(label: String, selected: Boolean, key: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(key)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick = null); Text(label) }
    }
}
@Composable
internal fun SettingSlider(label: String, value: Int, range: IntRange, key: String, onChange: (Int) -> Unit) {
    var slider by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$label: ${slider.roundToInt()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    Slider(slider, onValueChange = { slider = it }, valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0), onValueChangeFinished = { onChange(slider.roundToInt()) }, modifier = Modifier.testTag(key))
}
@Composable
private fun AppSelectionDialog(apps: List<LauncherApp>, title: String, onDismiss: () -> Unit,
    selected: Set<String>? = null, onToggle: (LauncherApp) -> Unit = {}, onSelect: (LauncherApp) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_apps)) }, singleLine = true, modifier = Modifier.testTag("appPickerSearch"))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(apps.filter { pl.violetglass.launcher.apps.AppSearch.matches(it.label, it.component.packageName, query) }, key = { it.key }) { app ->
                    TextButton(onClick = { if (selected == null) onSelect(app) else onToggle(app) }, modifier = Modifier.fillMaxWidth().testTag("pickApp:${app.key}")) {
                        if (selected != null) Checkbox(app.key in selected, onCheckedChange = null)
                        Text(app.label, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("closeAppPicker")) { Text(stringResource(R.string.close)) } })
}
@Composable
private fun ShortcutSelectionDialog(model: LauncherViewModel, app: LauncherApp, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var failed by remember { mutableStateOf(false) }
    val shortcuts by produceState<List<android.content.pm.ShortcutInfo>?>(null, app.key) {
        try { value = model.repository.shortcuts(app) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { failed = true; value = emptyList() }
    }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.choose_shortcut)) }, text = {
        when {
            shortcuts == null -> CircularProgressIndicator()
            failed -> Text(stringResource(R.string.shortcuts_need_home))
            shortcuts!!.isEmpty() -> Text(stringResource(R.string.shortcuts_empty))
            else -> Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                shortcuts!!.forEach { shortcut -> TextButton(onClick = { onSelect(shortcut.id) }) { Text(shortcut.shortLabel.toString()) } }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}


