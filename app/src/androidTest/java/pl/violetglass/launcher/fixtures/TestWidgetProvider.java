package pl.violetglass.launcher.fixtures;
import android.app.PendingIntent;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;
import pl.violetglass.launcher.test.R;

/** A real RemoteViews provider, present only in the separately installed instrumentation APK. */
public class TestWidgetProvider extends AppWidgetProvider {
    public static final String INCREMENT = "pl.violetglass.launcher.test.INCREMENT";
    public static void update(Context context, int id) {
        int count = context.getSharedPreferences("fixture", 0).getInt("count_" + id, 0);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.test_widget);
        views.setTextViewText(R.id.widgetCounter, context.getString(R.string.widget_counter, count));
        Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id);
        views.setTextViewText(R.id.widgetSize, "Rozmiar: " + options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) + " × " + options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT));
        Intent tap = new Intent(context, TestWidgetProvider.class).setAction(INCREMENT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        views.setOnClickPendingIntent(R.id.widgetButton, PendingIntent.getBroadcast(context, id, tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        AppWidgetManager.getInstance(context).updateAppWidget(id, views);
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { for (int id : ids) update(context, id); }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) { update(context, id); }
    @Override public void onReceive(Context context, Intent intent) {
        if (INCREMENT.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
            int count = context.getSharedPreferences("fixture", 0).getInt("count_" + id, 0);
            context.getSharedPreferences("fixture", 0).edit().putInt("count_" + id, count + 1).apply();
            update(context, id);
        } else super.onReceive(context, intent);
    }
    @Override public void onDeleted(Context context, int[] ids) { for (int id : ids) context.getSharedPreferences("fixture", 0).edit().remove("count_" + id).apply(); }
}
