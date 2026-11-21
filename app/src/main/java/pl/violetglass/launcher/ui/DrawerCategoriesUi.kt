package pl.violetglass.launcher.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.violetglass.launcher.R
import pl.violetglass.launcher.apps.AppSearch
import pl.violetglass.launcher.apps.LauncherApp
import pl.violetglass.launcher.settings.MAX_CATEGORY_APPS
import pl.violetglass.launcher.settings.MAX_CATEGORY_NAME
import pl.violetglass.launcher.settings.MAX_DRAWER_CATEGORIES
import pl.violetglass.launcher.settings.GlassTarget

@Composable
internal fun drawerTextShadow(target: GlassTarget = GlassTarget.DRAWER): Shadow? {
    val settings = LocalLauncherPreferences.current.glass
    if (!settings.enabled || target in settings.disabledTargets || settings.transparency == 0) return null
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 1.4.dp.toPx() }
    return Shadow(MaterialTheme.colorScheme.surface.copy(alpha = settings.transparency / 100f), blurRadius = radius)
}
@Composable
internal fun DrawerSearchHint(modifier: Modifier = Modifier) {
    val glass = LocalLauncherPreferences.current.glass
    val outlined = LocalGlassPolicy.current.enabled && GlassTarget.SEARCH !in glass.disabledTargets && glass.transparency > 0
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = if (outlined) Color.White else MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
    )
    DrawerAppLabel(stringResource(R.string.search_apps), modifier, style, outlined, maxLines = 1, singleWordSize = null)
}

@Composable
internal fun DrawerAction(onClick: () -> Unit, modifier: Modifier, label: String) {
    val colors = MaterialTheme.colorScheme
    FilledTonalButton(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DrawerCategoryBar(categories: Map<String, Set<String>>, selected: String?, onSelect: (String?) -> Unit,
    onManage: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chipColors = FilterChipDefaults.filterChipColors(containerColor = colors.surfaceContainer,
        labelColor = colors.onSurface, selectedContainerColor = colors.primary, selectedLabelColor = colors.onPrimary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(selected == null, { onSelect(null) }, { Text(stringResource(R.string.drawer_category_all)) },
                    modifier = Modifier.testTag("drawerCategoryAll"), colors = chipColors)
            }
            items(categories.keys.toList(), key = { it }) { name ->
                FilterChip(selected == name, { onSelect(name) }, { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.widthIn(max = 180.dp).testTag("drawerCategory:$name"), colors = chipColors)
            }
        }
        DrawerAction(onManage, Modifier.testTag("drawerCategoriesManage"), stringResource(R.string.drawer_categories))
    }
}

@Composable
internal fun DrawerCategoryDialog(categories: Map<String, Set<String>>, apps: List<LauncherApp>, selected: String?,
    onSave: (oldName: String?, name: String, apps: Set<String>) -> Unit, onDelete: (String) -> Unit,
    onDismiss: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(selected != null) }
    var original by rememberSaveable { mutableStateOf(selected) }
    if(!editing) {
        GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.drawer_categories)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(categories.keys.toList(), key = { it }) { name ->
                        TextButton(onClick = { original = name; editing = true }) { Text(name) }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { original = null; editing = true }, enabled = categories.size < MAX_DRAWER_CATEGORIES,
                    modifier = Modifier.testTag("drawerCategoryNew")) { Text(stringResource(R.string.drawer_category_new)) }
            }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
    } else {
        val oldName = original
        var name by rememberSaveable(oldName) { mutableStateOf(oldName.orEmpty()) }
        var query by rememberSaveable(oldName) { mutableStateOf("") }
        var appKeys by rememberSaveable(oldName) { mutableStateOf(ArrayList(categories[oldName].orEmpty())) }
        val visible = remember(apps, query) { apps.filter { AppSearch.matches(it.label, it.component.packageName, query) } }
        val chosen = remember(appKeys) { appKeys.toSet() }
        val trimmed = name.trim()
        val duplicate = categories.keys.any { it != oldName && it.equals(trimmed, ignoreCase = true) }
        GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.drawer_category_edit)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(name, { if(it.length <= MAX_CATEGORY_NAME && it.none(Char::isISOControl)) name = it },
                        label = { Text(stringResource(R.string.drawer_category_name)) }, isError = duplicate,
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("drawerCategoryName"))
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_apps)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("drawerCategorySearch"))
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(visible, key = { it.key }) { app ->
                            val checked = app.key in chosen
                            val available = checked || chosen.size < MAX_CATEGORY_APPS
                            Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = available, role = Role.Checkbox,
                                onValueChange = { appKeys = ArrayList(if(it) chosen + app.key else chosen - app.key) })
                                .testTag("drawerCategoryApp:${app.key}"), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null, enabled = available, modifier = Modifier.padding(12.dp))
                                Text(app.label, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { onSave(oldName, trimmed, chosen) }, enabled = trimmed.isNotEmpty() && !duplicate,
                    modifier = Modifier.testTag("drawerCategorySave")) { Text(stringResource(R.string.save)) }
            }, dismissButton = {
                if(oldName != null) TextButton(onClick = { onDelete(oldName) }, modifier = Modifier.testTag("drawerCategoryDelete")) {
                    Text(stringResource(R.string.drawer_category_delete))
                }
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
            })
    }
}
