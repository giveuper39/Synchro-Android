package dev.synchro.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import androidx.core.content.FileProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Bounded image clipboard access. Blocking image work belongs on a worker thread. */
public final class ClipboardImages {
  public static final int MAX_BYTES = 32 * 1024 * 1024;
  public static final long MAX_PIXELS = 64_000_000L;
  public static final String ORIGIN_KEY = "dev.synchro.clipboard.origin";
  private static final Object PUBLISH_LOCK = new Object();

  private ClipboardImages() {}

  /** Read only the current image, while this Activity owns foreground input focus. */
  public static byte[] readLastImage(Activity activity) throws IOException {
    if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus())
      throw new IOException("Откройте Synchro, чтобы прочитать изображение из буфера");
    ClipboardManager manager = activity.getSystemService(ClipboardManager.class);
    ClipData clip;
    try {
      clip = manager == null ? null : manager.getPrimaryClip();
    } catch (SecurityException error) {
      throw new IOException("Нет доступа к системному буферу");
    }
    if (clip == null || clip.getItemCount() == 0) return null;
    Uri uri = clip.getItemAt(0).getUri();
    return uri == null ? null : readUri(activity, uri);
  }

  /** Accept a user-shared content URI; file paths and arbitrary Intent extras are rejected. */
  public static byte[] loadImage(Activity activity, Intent intent) throws IOException {
    if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
    Uri uri;
    try {
      if (Build.VERSION.SDK_INT >= 33)
        uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
      else uri = legacyStream(intent);
    } catch (RuntimeException error) {
      throw new IOException("Приложение передало некорректное изображение");
    }
    if (uri == null) return null;
    return readUri(activity, uri);
  }

  @SuppressWarnings("deprecation")
  private static Uri legacyStream(Intent intent) {
    Object value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
    return value instanceof Uri ? (Uri) value : null;
  }

  private static byte[] readUri(Context context, Uri uri) throws IOException {
    if (!"content".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() == null)
      throw new IOException("Нужно изображение из приложения, а не путь к файлу");
    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
      if (input == null) throw new IOException("Изображение недоступно");
      return canonicalPng(readBounded(input));
    } catch (SecurityException error) {
      throw new IOException("Приложение не предоставило доступ к изображению");
    }
  }

  /** Read only the fixed ADB staging file; callers cannot select a different path. */
  static byte[] readPcStagingImage(Context context) throws IOException {
    File external = context.getExternalFilesDir(null);
    if (external == null) throw new IOException("Хранилище приложения недоступно");
    File file = new File(external, "clipboard/from-pc.png");
    if (!file.isFile() || file.length() <= 0 || file.length() > MAX_BYTES)
      throw new IOException("Некорректный размер изображения");
    byte[] raw;
    try (InputStream input = new FileInputStream(file)) {
      raw = readBounded(input);
    }
    if (!isPng(raw)) throw new IOException("Ожидается изображение PNG");
    return raw; // Full pixel validation happens once, immediately before publication.
  }

  static void clearPcStagingImage(Context context) {
    File external = context.getExternalFilesDir(null);
    if (external != null) new File(external, "clipboard/from-pc.png").delete();
  }

  /** Publish one current PNG through a private provider, preserving bridge echo metadata. */
  public static Uri publishPcImage(Context context, byte[] encoded, String origin)
      throws IOException {
    if (origin == null || !origin.matches("[a-fA-F0-9]{32}"))
      throw new IOException("Некорректный идентификатор передачи");
    byte[] png = canonicalPng(encoded);
    synchronized (PUBLISH_LOCK) {
      File external = context.getExternalFilesDir(null);
      if (external == null) throw new IOException("Хранилище приложения недоступно");
      File folder = new File(external, "clipboard/published");
      if (!folder.isDirectory() && !folder.mkdirs())
        throw new IOException("Не удалось сохранить изображение");
      File temporary = new File(folder, "latest.tmp");
      File current = new File(folder, "latest.png");
      try {
        try (FileOutputStream output = new FileOutputStream(temporary)) {
          output.write(png);
          output.getFD().sync();
        }
        try {
          Files.move(
              temporary.toPath(),
              current.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
          Files.move(temporary.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Uri uri =
            FileProvider.getUriForFile(context, context.getPackageName() + ".clipboard", current);
        ClipData clip = ClipData.newUri(context.getContentResolver(), "Synchro", uri);
        PersistableBundle extras = new PersistableBundle();
        extras.putString(ORIGIN_KEY, origin);
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) throw new IOException("Системный буфер недоступен");
        clipboard.setPrimaryClip(clip);
        return uri;
      } catch (IllegalArgumentException | SecurityException error) {
        throw new IOException("Не удалось скопировать изображение в системный буфер");
      } finally {
        if (temporary.exists()) temporary.delete();
      }
    }
  }

  /** Convenience for an explicit foreground “copy image” action. */
  public static Uri publishPcImage(Context context, byte[] encoded) throws IOException {
    return publishPcImage(context, encoded, UUID.randomUUID().toString().replace("-", ""));
  }

  static byte[] canonicalPng(byte[] encoded) throws IOException {
    if (encoded == null || encoded.length == 0 || encoded.length > MAX_BYTES)
      throw new IOException("Изображение должно быть не больше 32 МБ");
    if (!supportedImage(encoded)) throw new IOException("Поддерживаются PNG, JPEG и WebP");
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS)
      throw new IOException("Изображение повреждено или превышает 64 мегапикселя");
    Bitmap bitmap = null;
    try {
      bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
      if (bitmap == null) throw new IOException("Не удалось прочитать изображение");
      Bitmap upright = upright(bitmap, encoded);
      if (upright != bitmap) {
        bitmap.recycle();
        bitmap = upright;
      }
      LimitedOutput output = new LimitedOutput();
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        throw new IOException("Не удалось подготовить изображение");
      return output.toByteArray();
    } catch (ImageTooLarge error) {
      throw new IOException("Изображение PNG должно быть не больше 32 МБ");
    } catch (OutOfMemoryError error) {
      throw new IOException("Недостаточно памяти для этого изображения");
    } finally {
      if (bitmap != null) bitmap.recycle();
    }
  }

  private static Bitmap upright(Bitmap bitmap, byte[] encoded) {
    int orientation = ExifInterface.ORIENTATION_NORMAL;
    try (InputStream input = new ByteArrayInputStream(encoded)) {
      orientation =
          new ExifInterface(input)
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
    } catch (IOException ignored) {
      // Image codecs need not include EXIF; decoding has already validated pixels.
    }
    Matrix transform = new Matrix();
    switch (orientation) {
      case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
        transform.setScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_180:
        transform.setRotate(180);
        break;
      case ExifInterface.ORIENTATION_FLIP_VERTICAL:
        transform.setScale(1, -1);
        break;
      case ExifInterface.ORIENTATION_TRANSPOSE:
        transform.setRotate(90);
        transform.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_90:
        transform.setRotate(90);
        break;
      case ExifInterface.ORIENTATION_TRANSVERSE:
        transform.setRotate(270);
        transform.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_270:
        transform.setRotate(270);
        break;
      default:
        return bitmap;
    }
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
  }

  private static byte[] readBounded(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[16 * 1024];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (count == 0) continue;
      if (output.size() > MAX_BYTES - count)
        throw new IOException("Изображение должно быть не больше 32 МБ");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static boolean supportedImage(byte[] data) {
    return isPng(data)
        || (data.length >= 3
            && (data[0] & 255) == 255
            && (data[1] & 255) == 216
            && (data[2] & 255) == 255)
        || (data.length >= 12
            && data[0] == 'R'
            && data[1] == 'I'
            && data[2] == 'F'
            && data[3] == 'F'
            && data[8] == 'W'
            && data[9] == 'E'
            && data[10] == 'B'
            && data[11] == 'P');
  }

  private static boolean isPng(byte[] data) {
    return data.length >= 8
        && (data[0] & 255) == 137
        && data[1] == 80
        && data[2] == 78
        && data[3] == 71
        && data[4] == 13
        && data[5] == 10
        && data[6] == 26
        && data[7] == 10;
  }

  private static final class ImageTooLarge extends RuntimeException {}

  private static final class LimitedOutput extends ByteArrayOutputStream {
    @Override
    public synchronized void write(int value) {
      if (count >= MAX_BYTES) throw new ImageTooLarge();
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) {
      if (length > MAX_BYTES - count) throw new ImageTooLarge();
      super.write(buffer, offset, length);
    }
  }
}
