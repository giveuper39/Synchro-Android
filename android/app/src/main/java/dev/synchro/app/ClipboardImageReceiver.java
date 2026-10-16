package dev.synchro.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, DUMP-permission-protected ADB delivery of the current PC image. */
public final class ClipboardImageReceiver extends BroadcastReceiver {
  private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null
        || !(context.getPackageName() + ".APPLY_CLIPBOARD_IMAGE").equals(intent.getAction())) {
      setResultCode(Activity.RESULT_CANCELED);
      return;
    }
    String origin = intent.getStringExtra("origin");
    if (origin == null || !origin.matches("[a-fA-F0-9]{32}")) {
      setResultCode(Activity.RESULT_CANCELED);
      setResultData("invalid_origin");
      return;
    }
    PendingResult result = goAsync();
    Context application = context.getApplicationContext();
    IMAGES.execute(
        () -> {
          try {
            byte[] png = ClipboardImages.readPcStagingImage(application);
            ClipboardImages.publishPcImage(application, png, origin);
            ClipboardImages.clearPcStagingImage(application);
            result.setResultCode(Activity.RESULT_OK);
            result.setResultData("applied");
          } catch (Exception error) {
            // Providers and codecs may include private data in exception text.
            // Return a neutral machine result; never log the image/URI/error.
            result.setResultCode(Activity.RESULT_CANCELED);
            result.setResultData("image_unavailable");
          } finally {
            result.finish();
          }
        });
  }
}
