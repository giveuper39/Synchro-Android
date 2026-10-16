package dev.synchro.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native source selection, capabilities and real screenshots against synthetic-only media. */
final class DesignChecks {
  private final Instrumentation host;
  private final Bundle args;
  private Activity activity;

  DesignChecks(Instrumentation host, Bundle args) {
    this.host = host;
    this.args = args;
  }

  private Object field(Object value, String name) throws Exception {
    Field f = value.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(value);
  }

  private void await(java.util.function.BooleanSupplier predicate) {
    long end = SystemClock.elapsedRealtime() + 14000;
    while (SystemClock.elapsedRealtime() < end) {
      AtomicBoolean ok = new AtomicBoolean();
      host.runOnMainSync(() -> ok.set(predicate.getAsBoolean()));
      if (ok.get()) return;
      SystemClock.sleep(40);
    }
    throw new AssertionError("Native UI state timed out");
  }

  private View find(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View v = find(((ViewGroup) root).getChildAt(i), text);
        if (v != null) return v;
      }
    return null;
  }

  private void screenshot(String name) throws Exception {
    SystemClock.sleep(500);
    Bitmap b = host.getUiAutomation().takeScreenshot();
    if (b == null) throw new AssertionError("Missing screenshot");
    try (FileOutputStream out =
        new FileOutputStream(new File(host.getTargetContext().getExternalFilesDir(null), name))) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
  }

  private Intent launch(Class<?> type) {
    return new Intent()
        .setClassName(host.getTargetContext(), type.getName())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  void run() {
    Bundle result = new Bundle();
    try {
      String server = args.getString("server"), token = args.getString("token");
      if (new java.net.URI(server).getPort() != 18765)
        throw new IllegalArgumentException("Synthetic fixture only");
      ApiClient api = new ApiClient(server, token);
      api.request("POST", "/api/players/select", "{\"id\":\"potplayer\"}");
      activity =
          host.startActivitySync(
              launch(MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                  .putExtra("server", server)
                  .putExtra("token", token));
      host.runOnMainSync(
          () -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
      await(
          () ->
              ((TextView) activity.findViewById(R.id.source_name))
                  .getText()
                  .toString()
                  .equals("PotPlayer"));
      screenshot("Synchro-Android-Watch.png");
      host.runOnMainSync(() -> activity.findViewById(R.id.source_selector).performClick());
      final BottomSheetDialog[] dialog = new BottomSheetDialog[1];
      long end = SystemClock.elapsedRealtime() + 10000;
      while (SystemClock.elapsedRealtime() < end) {
        dialog[0] = (BottomSheetDialog) field(activity, "sourcesDialog");
        if (dialog[0] != null && dialog[0].isShowing()) break;
        SystemClock.sleep(30);
      }
      if (dialog[0] == null) throw new AssertionError("Source chooser did not open");
      screenshot("Synchro-Android-Sources.png");
      host.runOnMainSync(
          () -> {
            View row = find(dialog[0].getWindow().getDecorView(), "Медиаплеер Windows");
            while (row != null && !row.isClickable() && row.getParent() instanceof View)
              row = (View) row.getParent();
            if (row == null) throw new AssertionError("Windows Media Player row missing");
            row.performClick();
          });
      await(
          () ->
              ((TextView) activity.findViewById(R.id.source_name))
                  .getText()
                  .toString()
                  .equals("Медиаплеер Windows"));
      if (!api.request("GET", "/api/players", null).getString("selected").equals("windows"))
        throw new AssertionError("PC source selection not applied");
      host.runOnMainSync(() -> activity.findViewById(R.id.takeover).performClick());
      PlaybackController playback = (PlaybackController) field(activity, "playback");
      await(() -> playback.canResumeBuffered() && !playback.isPaused());
      ReceiverUi ui = (ReceiverUi) field(activity, "ui");
      host.runOnMainSync(ui::showControls);
      screenshot("Synchro-Android-Player.png");
      AtomicBoolean hidden = new AtomicBoolean();
      host.runOnMainSync(
          () ->
              hidden.set(
                  activity.findViewById(R.id.audio).getVisibility() == View.GONE
                      && activity.findViewById(R.id.subtitles).getVisibility() == View.GONE));
      if (!hidden.get()) throw new AssertionError("Unsupported Windows controls are shown");
      try {
        api.request("POST", "/api/players/select", "{\"id\":\"mpv\"}");
        throw new AssertionError("Source changed during phone playback");
      } catch (ApiClient.ApiException expected) {
        if (expected.status != 409) throw expected;
      }
      host.runOnMainSync(() -> activity.findViewById(R.id.play_pause).performClick());
      await(playback::isPaused);
      SystemClock.sleep(1300);
      host.runOnMainSync(activity::finish);
      SystemClock.sleep(500);
      api.request("POST", "/api/players/select", "{\"id\":\"potplayer\"}");
      result.putBoolean("windowsSourceAndCapabilities", true);
      result.putBoolean("activeSourceSwitchRejected", true);
      api.request("POST", "/api/clipboard/mode", "{\"enabled\":true,\"device\":\"synthetic\"}");
      activity =
          host.startActivitySync(launch(SyncActivity.class).putExtra("section", "clipboard"));
      SystemClock.sleep(1100);
      screenshot("Synchro-Android-Clipboard.png");
      host.runOnMainSync(activity::finish);
      api.request(
          "POST",
          "/api/links",
          "{\"target\":\"phone\",\"url\":\"https://example.org/weekend\",\"title\":\"Что посмотреть"
              + " в выходные\",\"requestId\":\"design-preview-link\"}");
      activity = host.startActivitySync(launch(SyncActivity.class).putExtra("section", "tabs"));
      SystemClock.sleep(1100);
      screenshot("Synchro-Android-Tabs.png");
      host.runOnMainSync(activity::finish);
      result.putString("result", "passed");
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.toString());
      host.finish(Activity.RESULT_CANCELED, result);
    }
  }
}
