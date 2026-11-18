package pl.violetglass.launcher.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.*
import pl.violetglass.launcher.workspace.*

class WidgetRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED || intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1) != WIDGET_HOST_ID) return
        val old = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: return
        val new = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
        if (old.size != new.size || new.any { it <= 0 } || new.distinct().size != new.size) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = LayoutRepository(LayoutDatabase.get(context))
                repository.initialize()
                val mapping = old.zip(new).toMap()
                repository.edit { state -> state.copy(items = state.items.map { item ->
                    mapping[item.widgetId]?.let { item.copy(widgetId = it) } ?: item
                }) }
                val manager = AppWidgetManager.getInstance(context)
                new.forEach { manager.updateAppWidgetOptions(it, Bundle().apply { putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true) }) }
            } catch (_: Exception) { /* Keep the previous layout; missing bindings remain visible and can be rebound. */ }
            finally { result.finish(); coroutineContext.cancel() }
        }
    }
}
