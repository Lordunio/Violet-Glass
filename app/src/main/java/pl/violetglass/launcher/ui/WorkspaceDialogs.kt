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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.violetglass.launcher.R
import pl.violetglass.launcher.workspace.*

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(text, { text = it.take(48) }, singleLine = true, modifier = Modifier.testTag("nameInput"))
    }, confirmButton = {
        TextButton(onClick = { onSave(text.trim()); onDismiss() }, enabled = text.isNotBlank(), modifier = Modifier.testTag("saveName")) { Text(stringResource(R.string.save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ItemMenu(payload: ItemPayload, isFolder: Boolean, onDismiss: () -> Unit,
    onAdd: (String) -> Unit, onMove: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit,
    onIcon: () -> Unit = {},
    onUninstall: (() -> Unit)? = null,
) {
    val locked = LocalLauncherPreferences.current.locked
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(payload.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (onUninstall != null && !isFolder) TextButton(onClick = { onUninstall(); onDismiss() }, modifier = Modifier.testTag("uninstallApp")) { Text(stringResource(R.string.uninstall_app)) }
            if(!isFolder) TextButton(onClick = { onIcon(); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("changeIcon")) { Text(stringResource(R.string.change_icon)) }
            if (payload.itemId == null) {
                TextButton(onClick = { onAdd("home"); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("addHome")) { Text(stringResource(R.string.add_home)) }
                TextButton(onClick = { onAdd(DOCK); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("addDock")) { Text(stringResource(R.string.add_dock)) }
            }
            TextButton(onClick = { onMove(); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("moveItem")) { Text(stringResource(R.string.move_item)) }
            if (payload.itemId != null) {
                TextButton(onClick = { onRename(); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("renameItem")) { Text(stringResource(R.string.rename)) }
                TextButton(onClick = { onRemove(); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("removeItem")) { Text(stringResource(if (isFolder) R.string.remove_folder else R.string.remove_shortcut)) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

@Composable
fun LayoutDialog(config: LayoutConfig, onDismiss: () -> Unit, onSave: (Int, Int, Int) -> Unit,
    onNewFolder: () -> Unit, onRemovePage: () -> Unit, requestDefault: () -> Unit, homeSettings: () -> Unit, isDefault: Boolean,
    onWidgets: () -> Unit = {}, onPersonalization: () -> Unit = {},
) {
    val locked = LocalLauncherPreferences.current.locked
    var columns by rememberSaveable { mutableIntStateOf(config.columns) }
    var rows by rememberSaveable { mutableIntStateOf(config.rows) }
    var dock by rememberSaveable { mutableIntStateOf(config.dockSlots) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.layout_options)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            NumberRow(stringResource(R.string.columns), columns, 3..8, "columns") { columns = it }
            NumberRow(stringResource(R.string.rows), rows, 3..8, "rows") { rows = it }
            NumberRow(stringResource(R.string.dock_slots), dock, 3..7, "dockSlots") { dock = it }
            Text(stringResource(R.string.grid_reflow), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onDismiss(); onNewFolder() }, enabled = !locked, modifier = Modifier.testTag("newFolder")) { Text(stringResource(R.string.new_folder)) }
            if (config.pages > 1) TextButton(onClick = { onRemovePage(); onDismiss() }) { Text(stringResource(R.string.remove_last_page)) }
            TextButton(onClick = { onDismiss(); onWidgets() }, enabled = !locked, modifier = Modifier.testTag("addWidget")) { Text(stringResource(R.string.add_widget)) }
            TextButton(onClick = { onDismiss(); onPersonalization() }, modifier = Modifier.testTag("advancedSettings")) { Text(stringResource(R.string.personalization)) }
            if (locked) Text(stringResource(R.string.layout_locked))
            HorizontalDivider()
            if (!isDefault) TextButton(onClick = requestDefault, modifier = Modifier.testTag("defaultButton")) { Text(stringResource(R.string.default_launcher)) }
            TextButton(onClick = homeSettings) { Text(stringResource(R.string.home_settings)) }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(columns, rows, dock); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("saveLayout")) { Text(stringResource(R.string.save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun NumberRow(label: String, value: Int, range: IntRange, key: String, onChange: (Int) -> Unit) {
    val decrease = stringResource(R.string.decrease) + ": " + label
    val increase = stringResource(R.string.increase) + ": " + label
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(value - 1) }, enabled = value > range.first,
            modifier = Modifier.testTag("$key-minus").semantics { contentDescription = decrease }) { Text("−") }
        Text(value.toString(), modifier = Modifier.testTag("$key-value"))
        TextButton(onClick = { onChange(value + 1) }, enabled = value < range.last,
            modifier = Modifier.testTag("$key-plus").semantics { contentDescription = increase }) { Text("+") }
    }
}


