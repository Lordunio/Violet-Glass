package pl.violetglass.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.violetglass.launcher.R
import pl.violetglass.launcher.settings.GlassTarget
import pl.violetglass.launcher.widgets.WidgetController
import pl.violetglass.launcher.workspace.*

val LocalWidgetController = staticCompositionLocalOf<WidgetController?> { null }

@Composable
fun HostedWidget(item: LayoutItem, registry: DragRegistry, revision: Long, onMenu: (ItemPayload) -> Unit, modifier: Modifier) {
    val controller = LocalWidgetController.current
    val info = remember(controller, item.widgetId, revision) { runCatching { item.widgetId?.let { controller?.manager?.getAppWidgetInfo(it) } }.getOrNull() }
    val payload = ItemPayload(item.id, title = item.title)
    val label = stringResource(R.string.item_options)
    val density = LocalDensity.current
    var size by remember(item.widgetId) { mutableStateOf(SizeF(0f, 0f)) }
    Box(modifier.then(Modifier.sourceBounds(registry, "item:${item.id}", payload))
        .testTag("widget:${item.id}").semantics { onLongClick(label) { onMenu(payload); true } }) {
        if (controller != null && info != null) {
            key(item.widgetId, info.provider) {
                AndroidView(factory = { context -> controller.host.createView(context, item.widgetId!!, info) },
                    modifier = Modifier.fillMaxSize().onSizeChanged { pixels ->
                        val next = SizeF(pixels.width / density.density, pixels.height / density.density)
                        if (next != size) size = next
                    },
                    update = { view ->
                        (view as? pl.violetglass.launcher.widgets.SafeWidgetView)?.reportSize(size)
                    })
            }
        } else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.widget_provider_missing), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller?.rebind(item) }) { Text(stringResource(R.string.widget_rebind)) }
            }
        }
    }
}

@Composable
fun WidgetMenu(item: LayoutItem, locked: Boolean, onDismiss: () -> Unit, onMove: () -> Unit, onResize: () -> Unit, onRemove: () -> Unit) {
    val controller = LocalWidgetController.current
    val info = remember(item.widgetId) { item.widgetId?.let { controller?.manager?.getAppWidgetInfo(it) } }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(item.title) }, text = {
        Column {
            Text(stringResource(R.string.widget_span, item.spanX, item.spanY))
            if (locked) Text(stringResource(R.string.layout_locked), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onMove(); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("moveWidget")) { Text(stringResource(R.string.move_item)) }
            TextButton(onClick = { onResize(); onDismiss() }, enabled = !locked && info != null && info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE, modifier = Modifier.testTag("resizeWidget")) { Text(stringResource(R.string.widget_resize)) }
            if (info?.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0)
                TextButton(onClick = { controller?.configure(item); onDismiss() }) { Text(stringResource(R.string.widget_configure)) }
            if (info == null) TextButton(onClick = { controller?.rebind(item); onDismiss() }) { Text(stringResource(R.string.widget_rebind)) }
            TextButton(onClick = { onRemove(); onDismiss() }, enabled = !locked, modifier = Modifier.testTag("removeWidget")) { Text(stringResource(R.string.widget_remove)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

@Composable
fun WidgetResizeDialog(item: LayoutItem, config: LayoutConfig, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    val controller = LocalWidgetController.current
    val info = remember(item.widgetId) { item.widgetId?.let { controller?.manager?.getAppWidgetInfo(it) } }
    val density = LocalDensity.current.density
    val cellWidth = (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width / density - LocalLauncherPreferences.current.margin * 2f) / config.columns
    val horizontal = info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    val vertical = info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    val minX = if (horizontal) kotlin.math.ceil((info!!.minResizeWidth / density + 16f) / cellWidth).toInt().coerceIn(1, config.columns) else item.spanX
    val minY = if (vertical) kotlin.math.ceil((info!!.minResizeHeight / density + 16f) / 84f).toInt().coerceIn(1, config.rows) else item.spanY
    val maxX = if (horizontal && info!!.maxResizeWidth > 0) kotlin.math.floor(info.maxResizeWidth / density / cellWidth).toInt().coerceIn(minX, config.columns) else if (horizontal) config.columns else item.spanX
    val maxY = if (vertical && info!!.maxResizeHeight > 0) kotlin.math.floor(info.maxResizeHeight / density / 84f).toInt().coerceIn(minY, config.rows) else if (vertical) config.rows else item.spanY
    var x by remember { mutableIntStateOf(item.spanX) }
    var y by remember { mutableIntStateOf(item.spanY) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.widget_resize)) }, text = {
        Column {
            Text(stringResource(R.string.widget_resize_hint), style = MaterialTheme.typography.bodySmall)
            NumberRow(stringResource(R.string.widget_width), x, minOf(minX, item.spanX)..maxOf(maxX, item.spanX), "widgetWidth") { x = it }
            NumberRow(stringResource(R.string.widget_height), y, minOf(minY, item.spanY)..maxOf(maxY, item.spanY), "widgetHeight") { y = it }
        }
    }, confirmButton = { TextButton(onClick = { onSave(x, y); onDismiss() }, modifier = Modifier.testTag("saveWidgetSize")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}



