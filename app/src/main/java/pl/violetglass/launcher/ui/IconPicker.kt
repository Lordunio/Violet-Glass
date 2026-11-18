package pl.violetglass.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pl.violetglass.launcher.R
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.icons.*

@Composable
fun IconPackSettings(model: LauncherViewModel) {
    val prefs = LocalLauncherPreferences.current
    var picking by rememberSaveable { mutableStateOf(false) }
    val packs by produceState<List<IconPack>?>(null, model) { value = model.repository.iconPacks.installed() }
    val selected = packs?.find { it.packageName == prefs.iconPack }
    Text(stringResource(R.string.icon_pack), style = MaterialTheme.typography.titleMedium)
    Text(if(prefs.iconPack.isEmpty()) stringResource(R.string.system_icons) else selected?.label ?: stringResource(R.string.icon_pack_missing), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { picking = true }, modifier = Modifier.testTag("chooseIconPack")) { Text(stringResource(R.string.choose_icon_pack)) }
    TextButton(onClick = { model.updatePreferences { it.copy(iconPack = "", iconOverrides = emptySet()) } }, modifier = Modifier.testTag("resetIcons")) { Text(stringResource(R.string.reset_icons)) }
    Text(stringResource(R.string.icon_pack_hint), style = MaterialTheme.typography.bodySmall)
    if(picking) IconPackDialog(packs, { picking = false }) { pack ->
        model.updatePreferences { it.copy(iconPack = pack) }; picking = false
    }
}

@Composable
private fun IconPackDialog(packs: List<IconPack>?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.icon_pack)) }, text = {
        androidx.compose.foundation.lazy.LazyColumn {
            item { TextButton(onClick = { onSelect("") }, modifier = Modifier.testTag("pack:system")) { Text(stringResource(R.string.system_icons)) } }
            if(packs == null) item { CircularProgressIndicator() }
            else if(packs.isEmpty()) item { Text(stringResource(R.string.no_icon_packs)) }
            else items(packs.size, key = { packs[it].packageName }) { index ->
                val pack = packs[index]
                TextButton(onClick = { onSelect(pack.packageName) }, modifier = Modifier.fillMaxWidth().testTag("pack:${pack.packageName}")) { Text(pack.label) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

@Composable
fun IndividualIconPicker(model: LauncherViewModel, appKey: String, title: String, onDismiss: () -> Unit) {
    val prefs = LocalLauncherPreferences.current
    val packs by produceState<List<IconPack>?>(null, model) { value = model.repository.iconPacks.installed() }
    var pack by rememberSaveable { mutableStateOf(decodeIconChoices(prefs.iconOverrides)[appKey]?.pack?.takeIf { it.isNotEmpty() } ?: prefs.iconPack) }
    var choosingPack by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val index by produceState<PackIndex?>(null, pack) { value = null; value = model.repository.iconPacks.index(pack) }
    fun select(choice: IconChoice?) {
        model.updatePreferences { current ->
            val choices = decodeIconChoices(current.iconOverrides).toMutableMap()
            if(choice == null) choices.remove(appKey) else choices[appKey] = choice
            current.copy(iconOverrides = choices.map { (key, icon) -> encodeIconChoice(key, icon) }.toSet())
        }
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(pl.violetglass.launcher.settings.GlassTarget.SETTINGS, Modifier.fillMaxWidth(.96f).fillMaxHeight(.9f)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.individual_icon_hint), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { select(null) }, modifier = Modifier.testTag("iconDefault")) { Text(stringResource(R.string.default_icon)) }
                    TextButton(onClick = { select(IconChoice()) }, modifier = Modifier.testTag("iconSystem")) { Text(stringResource(R.string.system_icons)) }
                }
                TextButton(onClick = { choosingPack = true }, modifier = Modifier.testTag("individualPack")) {
                    Text(packs?.find { it.packageName == pack }?.label ?: stringResource(R.string.choose_icon_pack))
                }
                OutlinedTextField(query, { query = it.take(100) }, singleLine = true, label = { Text(stringResource(R.string.search_icons)) }, modifier = Modifier.fillMaxWidth().testTag("iconQuery"))
                val filtered = remember(index, query) { index?.drawables?.filter { it.contains(query.trim(), true) }.orEmpty() }
                if(index == null) CircularProgressIndicator()
                else if(filtered.isEmpty()) Text(stringResource(R.string.no_matching_icons), modifier = Modifier.padding(12.dp))
                LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.weight(1f)) {
                    items(filtered, key = { it }) { drawable ->
                        val bitmap by produceState<android.graphics.Bitmap?>(null, pack, drawable) { value = model.repository.iconPacks.bitmap(pack, drawable) }
                        Column(Modifier.clickable(enabled = bitmap != null) { select(IconChoice(pack, drawable)) }.padding(8.dp).testTag("icon:$drawable"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(48.dp)) { bitmap?.let { Image(it.asImageBitmap(), contentDescription = drawable, modifier = Modifier.fillMaxSize()) } }
                            Text(drawable, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
    if(choosingPack) IconPackDialog(packs, { choosingPack = false }) { selected -> pack = selected; query = ""; choosingPack = false }
}
