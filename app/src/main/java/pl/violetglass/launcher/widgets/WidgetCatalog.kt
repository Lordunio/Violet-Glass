package pl.violetglass.launcher.widgets

import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.violetglass.launcher.apps.AppSearch
import java.text.Collator

data class WidgetEntry(val info: AppWidgetProviderInfo, val title: String, val application: String) {
    val key: String get() = info.provider.flattenToString()
    val packageName: String get() = info.provider.packageName
    fun matches(query: String) = AppSearch.matches("$application $title", packageName, query)
}
sealed interface WidgetPreview {
    data class Layout(val views: RemoteViews) : WidgetPreview
    data class Image(val bitmap: Bitmap) : WidgetPreview
    data object Unavailable : WidgetPreview
}

suspend fun WidgetController.catalog(): List<WidgetEntry> = withContext(Dispatchers.IO) {
    val collator = Collator.getInstance()
    providers().map { info ->
        val title = runCatching { label(info) }.getOrDefault(info.provider.shortClassName)
        val app = runCatching { context.packageManager.getApplicationInfo(info.provider.packageName, 0).loadLabel(context.packageManager).toString() }.getOrDefault(info.provider.packageName)
        WidgetEntry(info, title, app)
    }.sortedWith { a, b -> collator.compare(a.application, b.application).takeIf { it != 0 }
        ?: collator.compare(a.title, b.title).takeIf { it != 0 } ?: a.key.compareTo(b.key) }
}

/** Preview resources only: does not allocate/bind an appWidgetId or contact a provider. */
suspend fun WidgetController.preview(info: AppWidgetProviderInfo): WidgetPreview = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= 35 && info.generatedPreviewCategories and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0) {
        val generated = runCatching { manager.getWidgetPreview(info.provider, info.profile, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }.getOrNull()
        if (generated != null) return@withContext WidgetPreview.Layout(generated)
    }
    if (info.previewLayout != 0) return@withContext WidgetPreview.Layout(RemoteViews(info.provider.packageName, info.previewLayout))
    if (info.previewImage != 0) {
        val image = runCatching { info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi) }.getOrNull()
        if (image != null) {
            val width = image.intrinsicWidth.coerceAtLeast(1); val height = image.intrinsicHeight.coerceAtLeast(1)
            val scale = minOf(720f / width, 400f / height, 1f)
            return@withContext WidgetPreview.Image(image.toBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1)))
        }
    }
    WidgetPreview.Unavailable
}
