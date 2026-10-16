package dev.synchro.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/** Synthetic images only: codec bounds, own provider and background ADB delivery. */
final class ClipboardImageChecks {
  private final Instrumentation host;
  private final Bundle arguments;
  private Activity activity;

  ClipboardImageChecks(Instrumentation host, Bundle arguments) {
    this.host = host;
    this.arguments = arguments;
  }

  private void open() {
    activity =
        host.startActivitySync(
            new Intent(host.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while (SystemClock.elapsedRealtime() < deadline) {
      AtomicBoolean focused = new AtomicBoolean();
      host.runOnMainSync(() -> focused.set(activity.hasWindowFocus()));
      if (focused.get()) return;
      SystemClock.sleep(30);
    }
    throw new AssertionError("Fixture activity did not gain focus");
  }

  private static byte[] image(Bitmap.CompressFormat format) {
    Bitmap bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
    try {
      bitmap.eraseColor(0xff2e7ded);
      bitmap.setPixel(0, 0, 0xffe56334);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      if (!bitmap.compress(format, 95, bytes)) throw new AssertionError("Fixture codec failed");
      return bytes.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }

  private static void dimensions(byte[] image, int width, int height) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
    if (!"image/png".equals(bounds.outMimeType)
        || bounds.outWidth != width
        || bounds.outHeight != height)
      throw new AssertionError("Canonical dimensions or MIME mismatch");
  }

  private String shell(String command) throws IOException {
    try (ParcelFileDescriptor descriptor = host.getUiAutomation().executeShellCommand(command);
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
      return bytes.toString("UTF-8");
    }
  }

  private static void mustReject(byte[] image) throws IOException {
    try {
      ClipboardImages.canonicalPng(image);
      throw new AssertionError("Invalid/oversized image accepted");
    } catch (IOException expected) {
      // Expected validation failure, never inspect or log payloads.
    }
  }

  private static byte[] hugeDimensions(byte[] png) {
    byte[] result = png.clone();
    for (int offset : new int[] {16, 20}) {
      result[offset] = 0;
      result[offset + 1] = 1;
      result[offset + 2] = (byte) 0x86;
      result[offset + 3] = (byte) 0xa0; // 100,000 x 100,000: reject before allocation.
    }
    CRC32 crc = new CRC32();
    crc.update(result, 12, 17);
    long value = crc.getValue();
    for (int i = 0; i < 4; i++) result[29 + i] = (byte) (value >>> (24 - i * 8));
    return result;
  }

  private static byte[] rotatedJpeg(byte[] jpeg) throws IOException {
    // EXIF orientation 6 (90 degrees), one big-endian IFD entry, no private metadata.
    byte[] exif = {
      (byte) 0xff,
      (byte) 0xe1,
      0,
      34,
      69,
      120,
      105,
      102,
      0,
      0,
      77,
      77,
      0,
      42,
      0,
      0,
      0,
      8,
      0,
      1,
      1,
      18,
      0,
      3,
      0,
      0,
      0,
      1,
      0,
      6,
      0,
      0,
      0,
      0,
      0,
      0
    };
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(jpeg, 0, 2);
    output.write(exif);
    output.write(jpeg, 2, jpeg.length - 2);
    return output.toByteArray();
  }

  void run() {
    Bundle result = new Bundle();
    String origin = UUID.randomUUID().toString().replace("-", "");
    try {
      if (!"synthetic-emulator-only".equals(arguments.getString("imagesFixture")))
        throw new IllegalArgumentException("Explicit emulator fixture opt-in is required");
      byte[] png = image(Bitmap.CompressFormat.PNG);
      dimensions(ClipboardImages.canonicalPng(png), 4, 2);
      dimensions(ClipboardImages.canonicalPng(image(Bitmap.CompressFormat.JPEG)), 4, 2);
      dimensions(
          ClipboardImages.canonicalPng(rotatedJpeg(image(Bitmap.CompressFormat.JPEG))), 2, 4);
      mustReject(new byte[] {1, 2, 3});
      mustReject(new byte[ClipboardImages.MAX_BYTES + 1]);
      mustReject(hugeDimensions(png));
      result.putBoolean("codecsBoundsAndExif", true);
      open();
      try {
        ClipboardImages.loadImage(
            activity,
            new Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/private-image.png")));
        throw new AssertionError("file URI accepted");
      } catch (IOException expected) {
      }
      File external = host.getTargetContext().getExternalFilesDir(null);
      if (external == null) throw new AssertionError("No external app directory");
      File staging = new File(external, "clipboard/from-pc.png");
      if (!staging.getParentFile().isDirectory() && !staging.getParentFile().mkdirs())
        throw new AssertionError("No staging directory");
      try (FileOutputStream output = new FileOutputStream(staging)) {
        output.write(png);
      }
      shell("input keyevent 3");
      SystemClock.sleep(200);
      String packageId = host.getTargetContext().getPackageName();
      String response =
          shell(
              "am broadcast --receiver-foreground -a "
                  + packageId
                  + ".APPLY_CLIPBOARD_IMAGE -n "
                  + packageId
                  + "/"
                  + ClipboardImageReceiver.class.getName()
                  + " --es origin "
                  + origin);
      if (!response.contains("result=-1"))
        throw new AssertionError("Background image delivery rejected");
      if (staging.exists()) throw new AssertionError("Staging copy retained after publication");
      open();
      ClipboardManager manager = activity.getSystemService(ClipboardManager.class);
      ClipData clip = manager.getPrimaryClip();
      if (clip == null || !clip.getDescription().hasMimeType("image/png"))
        throw new AssertionError("System clipboard is not PNG");
      if (!origin.equals(clip.getDescription().getExtras().getString(ClipboardImages.ORIGIN_KEY)))
        throw new AssertionError("Bridge origin lost");
      Uri uri = clip.getItemAt(0).getUri();
      if (uri == null || !(packageId + ".clipboard").equals(uri.getAuthority()))
        throw new AssertionError("Clipboard provider mismatch");
      dimensions(ClipboardImages.readLastImage(activity), 4, 2);
      dimensions(
          ClipboardImages.loadImage(
              activity, new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)),
          4,
          2);
      File[] published = new File(external, "clipboard/published").listFiles();
      if (published == null
          || published.length != 1
          || !"latest.png".equals(published[0].getName()))
        throw new AssertionError("More than one image retained");
      result.putBoolean("backgroundClipboardAndProvider", true);
      result.putBoolean("originAndLatestOnly", true);
      Uri generic = Uri.parse("content://dev.potplayer.handoff.test.generic-images/photo");
      if (!"application/octet-stream".equals(activity.getContentResolver().getType(generic)))
        throw new AssertionError("Generic MIME fixture did not apply");
      ClipData copied = ClipData.newUri(activity.getContentResolver(), "Synthetic photo", generic);
      host.runOnMainSync(() -> activity.getSystemService(ClipboardManager.class).setPrimaryClip(copied));
      dimensions(ClipboardImages.readLastImage(activity), 4, 2);
      result.putBoolean("genericProviderMimeFixture", true);
      result.putString("result", "passed");
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("result", "failed");
      result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
      host.finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (activity != null) host.runOnMainSync(activity::finish);
    }
  }
}
