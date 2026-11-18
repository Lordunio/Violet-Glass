package pl.violetglass.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.violetglass.launcher.R
import pl.violetglass.launcher.home.LauncherViewModel
import java.text.DateFormat
import java.util.Date

data class BackupPicker(val export: () -> Unit, val open: () -> Unit)
val LocalBackupPicker = staticCompositionLocalOf<BackupPicker?> { null }

@Composable
fun BackupControls(model: LauncherViewModel) {
    val state by model.backup.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val picker = LocalBackupPicker.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.backup_scope))
    Text(stringResource(R.string.backup_private), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { picker?.export?.invoke() }, enabled = picker != null && !state.busy && workspace.layout != null,
        modifier = Modifier.fillMaxWidth().testTag("backupExport")) { Text(stringResource(R.string.backup_export)) }
    OutlinedButton(onClick = { picker?.open?.invoke() }, enabled = picker != null && !state.busy,
        modifier = Modifier.fillMaxWidth().testTag("backupImport")) { Text(stringResource(R.string.backup_import)) }
    state.message?.let { Text(stringResource(it), Modifier.padding(vertical = 10.dp).testTag("backupMessage")) }
    state.preview?.let { doc ->
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.backup_preview), style = MaterialTheme.typography.titleLarge)
        Text(DateFormat.getDateTimeInstance().format(Date(doc.createdAt)))
        Text(stringResource(R.string.backup_summary, doc.layout.config.pages, doc.shortcuts, doc.folders, doc.widgets), Modifier.testTag("backupSummary"))
        Text(stringResource(R.string.backup_grid, doc.layout.config.columns, doc.layout.config.rows, doc.layout.config.dockSlots))
        Text(stringResource(if(doc.pet == null) R.string.backup_pet_keep else R.string.backup_pet_restore))
        Text(stringResource(R.string.backup_replace), Modifier.padding(vertical = 8.dp))
        if(doc.widgets > 0) Text(stringResource(R.string.backup_widgets))
        val available by model.state.collectAsStateWithLifecycle()
        val missing = doc.layout.items.count { it.appKey != null && available.apps.none { app -> app.key == it.appKey } }
        if(!available.loading && missing > 0) Text(stringResource(R.string.backup_missing, missing))
        val locked = LocalLauncherPreferences.current.locked
        if(locked) Text(stringResource(R.string.backup_unlock))
        Button(onClick = { confirm = true }, enabled = !locked && !state.busy && model.pending() == null,
            modifier = Modifier.fillMaxWidth().testTag("backupRestore")) { Text(stringResource(R.string.backup_restore)) }
        TextButton(onClick = model::dismissBackupPreview, enabled = !state.busy, modifier = Modifier.testTag("backupCancel")) { Text(stringResource(R.string.cancel)) }
        if(confirm) GlassAlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; model.restoreBackup() }, modifier = Modifier.testTag("backupConfirm")) { Text(stringResource(R.string.backup_restore)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
    }
    Text(stringResource(R.string.backup_nova), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun BackupProgress(model: LauncherViewModel) {
    val state by model.backup.collectAsStateWithLifecycle()
    if(state.busy) Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp).testTag("backupBusy")) {
                CircularProgressIndicator()
                Text(stringResource(R.string.backup_busy), Modifier.padding(top = 12.dp))
            }
        }
    }
}
