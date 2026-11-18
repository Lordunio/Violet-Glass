package pl.violetglass.uninstallfixture;
public final class FixtureActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("Disposable emulator uninstall fixture");
        setContentView(label);
    }
}
