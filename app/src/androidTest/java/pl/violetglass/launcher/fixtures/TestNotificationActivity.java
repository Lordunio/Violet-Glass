package pl.violetglass.launcher.fixtures;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Runs in the test APK's standalone process. */
public class TestNotificationActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        update(this, getIntent());
        finish();
    }

    static void update(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int id = intent.getIntExtra("id", 941);
        if (intent.getBooleanExtra("cancel", false)) {
            manager.cancel(id);
        } else {
            String channelId = "violet_badge_fixture";
            NotificationChannel channel = new NotificationChannel(channelId, "Violet badge fixture", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(true);
            manager.createNotificationChannel(channel);
            String category = intent.getStringExtra("category");
            Notification.Builder notification = new Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Violet badge fixture")
                    .setCategory(category == null ? Notification.CATEGORY_MESSAGE : category)
                    .setNumber(3);
            String group = intent.getStringExtra("group");
            if (group != null) {
                notification.setGroup(group).setGroupSummary(intent.getBooleanExtra("summary", false));
            }
            manager.notify(id, notification.build());
        }
    }
}
