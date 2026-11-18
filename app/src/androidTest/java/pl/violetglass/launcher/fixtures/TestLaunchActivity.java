package pl.violetglass.launcher.fixtures;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
public class TestLaunchActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this); text.setTextSize(24); text.setPadding(32, 64, 32, 32);
        text.setText(Intent.ACTION_VIEW.equals(getIntent().getAction()) ? "Uruchomiono skrót testowy" : "Uruchomiono aplikację testową");
        setContentView(text);
    }
}
