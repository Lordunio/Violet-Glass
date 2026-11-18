package pl.violetglass.launcher.ui

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import pl.violetglass.launcher.R
import pl.violetglass.launcher.settings.GlassTarget
import pl.violetglass.launcher.widgets.*

@Composable
fun WidgetPicker(controller: WidgetController, page: Int, onDismiss: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val entries by produceState<List<WidgetEntry>?>(null, controller, retry) {
        failed = false
        try { value = controller.catalog() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true; value = emptyList() }
    }
    val groups = remember(entries, query) { entries.orEmpty().filter { it.matches(query) }.groupBy { it.packageName } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(GlassTarget.PANELS, Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f), backdrop = LocalWorkspaceBackdrop.current) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.add_widget), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.widget_picker_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.widget_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("widgetSearch"))
                if (entries == null) CircularProgressIndicator()
                if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                if (entries != null && groups.isEmpty()) Text(stringResource(if(query.isBlank()) R.string.widget_none else R.string.widget_search_empty), Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.weight(1f).testTag("widgetProviders"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    groups.forEach { (packageName, widgets) ->
                        val open = query.isNotBlank() || packageName in expanded
                        item(key = "app:$packageName", contentType = "application") {
                            FilledTonalButton(onClick = { expanded = if(packageName in expanded) expanded - packageName else expanded + packageName },
                                modifier = Modifier.fillMaxWidth().testTag("widgetGroup:$packageName")) {
                                Column(Modifier.weight(1f)) {
                                    Text(widgets.first().application, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.widget_group_count, widgets.size), style = MaterialTheme.typography.labelMedium)
                                }
                                Text(if(open) "⌃" else "⌄")
                            }
                        }
                        if (open) items(widgets, key = { it.key }, contentType = { "widget" }) { entry ->
                            WidgetPreviewCard(controller, entry) { controller.add(entry.info, page); onDismiss() }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun WidgetPreviewCard(controller: WidgetController, entry: WidgetEntry, onSelect: () -> Unit) {
    val preview by produceState<WidgetPreview?>(null, controller, entry.key) {
        try { value = controller.preview(entry.info) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { value = WidgetPreview.Unavailable }
    }
    var renderFailed by remember(entry.key) { mutableStateOf(false) }
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth().testTag("provider:${entry.key}")) {
        Column(Modifier.padding(12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            val x = entry.info.targetCellWidth.coerceAtLeast(1); val y = entry.info.targetCellHeight.coerceAtLeast(1)
            Text(stringResource(R.string.widget_span, x, y), style = MaterialTheme.typography.labelMedium)
            Box(Modifier.fillMaxWidth().height(150.dp).testTag("widgetPreview:${entry.key}"), contentAlignment = Alignment.Center) {
                when(val source = preview) {
                    null -> CircularProgressIndicator()
                    is WidgetPreview.Image -> Image(source.bitmap.asImageBitmap(), contentDescription = stringResource(R.string.widget_preview), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    is WidgetPreview.Layout -> if(!renderFailed) AndroidView(factory = { context ->
                        SafeWidgetView(context).apply {
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            setAppWidget(-1, entry.info)
                            try { updateAppWidget(source.views) } catch (_: RuntimeException) { renderFailed = true }
                        }
                    }, modifier = Modifier.fillMaxSize().testTag("widgetPreviewLayout:${entry.key}")) else Text(stringResource(R.string.widget_preview_missing))
                    WidgetPreview.Unavailable -> Text(stringResource(R.string.widget_preview_missing), style = MaterialTheme.typography.bodySmall)
                }
                // This layer blocks RemoteViews clicks: previewing never starts a provider action.
                Box(Modifier.matchParentSize().clickable(onClick = onSelect).clearAndSetSemantics {})
            }
        }
    }
}
