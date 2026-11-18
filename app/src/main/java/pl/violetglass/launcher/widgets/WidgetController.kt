package pl.violetglass.launcher.widgets

import android.app.Activity
import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.TextView
import android.graphics.Canvas
import pl.violetglass.launcher.R
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.workspace.*
import kotlin.math.ceil

const val WIDGET_HOST_ID = 3072
const val WIDGET_CONFIGURE_REQUEST = 65030

class SafeWidgetHost(context: Context) : AppWidgetHost(context, WIDGET_HOST_ID) {
    fun releaseViews() { clearViews() }
    override fun onCreateView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView = SafeWidgetView(context)
}
class SafeWidgetView(context: Context) : AppWidgetHostView(context) {
    private var failed = false
    private var reportedSize: android.util.SizeF? = null
    fun reportSize(size: android.util.SizeF) {
        if (size.width > 0 && size.height > 0 && size != reportedSize) {
            runCatching { updateAppWidgetSize(Bundle(), listOf(size)); reportedSize = size }
        }
    }
    override fun getErrorView(): View = TextView(context).apply { text = context.getString(R.string.widget_render_error); setTextColor(0xFFF5F0FA.toInt()); setBackgroundColor(0xFF211631.toInt()); setPadding(12, 12, 12, 12) }
    private fun showFailure() {
        if (!failed) { failed = true; post { removeAllViews(); addView(errorView) } }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        try { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        catch (_: RuntimeException) { setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec)); showFailure() }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        try { super.onLayout(changed, left, top, right, bottom) } catch (_: RuntimeException) { showFailure() }
    }
    override fun dispatchDraw(canvas: Canvas) {
        try { super.dispatchDraw(canvas) } catch (_: RuntimeException) { showFailure() }
    }
}

class WidgetController(
    private val activity: Activity, private val model: LauncherViewModel,
    private val launchBind: (Intent) -> Unit,
) {
    val context: Context get() = activity
    val host = SafeWidgetHost(activity.applicationContext)
    val manager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    fun start() { runCatching { host.startListening() }.onFailure { model.notify(R.string.widget_host_error) } }
    fun stop() { runCatching { host.stopListening() } }
    fun destroy() { stop(); host.releaseViews() }
    fun label(info: AppWidgetProviderInfo): String = info.loadLabel(activity.packageManager)
    fun providers(): List<AppWidgetProviderInfo> = manager.getInstalledProvidersForProfile(Process.myUserHandle())
        .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 && it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0 }
        .sortedBy { it.loadLabel(activity.packageManager).lowercase() }

    fun add(info: AppWidgetProviderInfo, page: Int, replaceId: String? = null) {
        if (model.pending() != null) { model.notify(R.string.widget_pending); return }
        if (model.preferences.value.locked && replaceId == null) { model.notify(R.string.layout_locked); return }
        val config = model.workspace.value.layout?.config ?: return
        val density = activity.resources.displayMetrics.density
        val cellWidth = activity.resources.configuration.screenWidthDp.toFloat() / config.columns
        val x = if (info.targetCellWidth > 0) info.targetCellWidth else ceil((info.minWidth / density + 16f) / cellWidth).toInt()
        val y = if (info.targetCellHeight > 0) info.targetCellHeight else ceil((info.minHeight / density + 16f) / 84f).toInt()
        if (replaceId == null && (x > config.columns || y > config.rows)) { model.notify(R.string.widget_too_large); return }
        try {
            val id = host.allocateAppWidgetId()
            model.setPending(PendingWidget(id, info.provider.flattenToString(), info.loadLabel(activity.packageManager), page, x.coerceAtLeast(1), y.coerceAtLeast(1), replaceId))
            val options = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }
            if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, options)) configureOrCommit()
            else launchBind(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options))
        } catch (_: RuntimeException) { model.cancelWidget(); model.notify(R.string.widget_bind_error) }
    }
    fun onBindResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK && validPending()) configureOrCommit()
        else model.cancelWidget()
    }
    private fun validPending(): Boolean {
        val request = model.pending() ?: return false
        return manager.getAppWidgetInfo(request.id)?.provider?.flattenToString() == request.provider
    }
    private fun configureOrCommit() {
        val request = model.pending() ?: return
        val info = manager.getAppWidgetInfo(request.id) ?: run { model.cancelWidget(); return }
        if (info.configure != null) {
            model.setPending(request.copy(stage = "configure"))
            try {
                // The public host API can configure non-exported provider activities; there is no public IntentSender getter.
                host.startAppWidgetConfigureActivityForResult(activity, request.id, 0, WIDGET_CONFIGURE_REQUEST, null)
            } catch (_: RuntimeException) {
                if (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0) model.commitWidget()
                else { model.cancelWidget(); model.notify(R.string.widget_configure_error) }
            }
        } else model.commitWidget()
    }
    fun onConfigurationResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK && validPending()) model.commitWidget() else model.cancelWidget()
    }
    fun configure(item: LayoutItem) {
        if (model.pending() != null) { model.notify(R.string.widget_pending); return }
        val info = item.widgetId?.let(manager::getAppWidgetInfo) ?: return
        if (info.configure == null || info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE == 0) return
        model.setPending(PendingWidget(item.widgetId, info.provider.flattenToString(), item.title, 0, item.spanX, item.spanY, item.id, configureOnly = true))
        try { host.startAppWidgetConfigureActivityForResult(activity, item.widgetId, 0, WIDGET_CONFIGURE_REQUEST, null) }
        catch (_: RuntimeException) { model.cancelWidget(); model.notify(R.string.widget_configure_error) }
    }
    fun rebind(item: LayoutItem) {
        val info = runCatching { providers().find { it.provider.flattenToString() == item.provider } }.getOrNull()
        if (info == null) model.notify(R.string.widget_provider_missing)
        else add(info, item.container.removePrefix("page:").toIntOrNull() ?: 0, item.id)
    }
    fun resumeCommitIfNeeded() { if (model.pending()?.stage == "commit") model.commitWidget() }
}




