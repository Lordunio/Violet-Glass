package pl.violetglass.launcher.fixtures;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** Test-only documents, in a separate APK. Never exposes arbitrary paths. */
public class BackupFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("backup-[a-zA-Z0-9-]+\\.json")) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), name);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        String name = uri.getLastPathSegment();
        if (name != null && name.matches("backup-[a-zA-Z0-9-]+\\.json")) return new File(getContext().getCacheDir(), name).delete() ? 1 : 0;
        return 0;
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
