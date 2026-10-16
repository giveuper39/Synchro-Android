package dev.synchro.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Synthetic-only provider in the instrumentation APK; never part of a release build. */
public final class GenericImageProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public String getType(Uri uri) {
    return "application/octet-stream";
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode) || !"/photo".equals(uri.getPath())) throw new FileNotFoundException();
    try {
      ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
      Bitmap image = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
      image.eraseColor(0xff32a8bc);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      image.compress(Bitmap.CompressFormat.PNG, 100, bytes);
      image.recycle();
      new Thread(
              () -> {
                try (java.io.OutputStream output =
                    new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                  output.write(bytes.toByteArray());
                } catch (IOException ignored) {
                }
              },
              "synthetic-image-provider")
          .start();
      return pipe[0];
    } catch (IOException error) {
      throw new FileNotFoundException("Synthetic image unavailable");
    }
  }

  @Override
  public Cursor query(Uri uri, String[] p, String s, String[] a, String sort) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
