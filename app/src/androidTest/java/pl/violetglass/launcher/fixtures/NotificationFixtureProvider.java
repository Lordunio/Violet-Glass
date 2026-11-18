package pl.violetglass.launcher.fixtures;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Lets tests post or cancel synchronously without changing the foreground activity. */
public class NotificationFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (!"update".equals(method) || extras == null) throw new IllegalArgumentException();
        TestNotificationActivity.update(getContext(), new Intent().putExtras(extras));
        return Bundle.EMPTY;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
