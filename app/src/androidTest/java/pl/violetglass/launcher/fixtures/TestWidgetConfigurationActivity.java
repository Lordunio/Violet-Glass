package pl.violetglass.launcher.fixtures;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TestWidgetConfigurationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
        setResult(RESULT_CANCELED);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 64, 32, 32);
        TextView text = new TextView(this); text.setText("Konfiguracja prawdziwego widgetu testowego"); layout.addView(text);
        Button save = new Button(this); save.setText("Zapisz widget testowy"); layout.addView(save);
        save.setOnClickListener(v -> { TestWidgetProvider.update(this, id); setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish(); });
        setContentView(layout);
    }
}
