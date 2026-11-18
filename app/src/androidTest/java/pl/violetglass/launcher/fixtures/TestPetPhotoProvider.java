package pl.violetglass.launcher.fixtures;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Standalone test-process provider, deliberately Java: no target-app Kotlin runtime dependency. */
public final class TestPetPhotoProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/photo.png".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), "pet-photo-fixture.png");
        if (!file.exists()) {
            Bitmap bitmap = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.LTGRAY);
            Paint paint = new Paint(); paint.setColor(Color.DKGRAY);
            canvas.drawRect(0, 780, 720, 1600, paint);
            paint.setColor(Color.GRAY); canvas.drawCircle(360, 330, 170, paint);
            try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            finally { bitmap.recycle(); }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
