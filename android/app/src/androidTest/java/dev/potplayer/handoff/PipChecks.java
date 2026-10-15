package dev.potplayer.handoff;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run only with the isolated synthetic fixture. Verifies mini-player lifecycle and heartbeats. */
final class PipChecks {
  private Bundle arguments;
  private Activity activity;
  private PlaybackController playback;
  private ApiClient api;

  private final Instrumentation host;

  PipChecks(Instrumentation host, Bundle arguments) {
    this.host = host;
    this.arguments = arguments;
  }

  private boolean check(java.util.function.BooleanSupplier predicate) {
    AtomicBoolean value = new AtomicBoolean();
    host.runOnMainSync(() -> value.set(predicate.getAsBoolean()));
    return value.get();
  }

  private void await(java.util.function.BooleanSupplier predicate) {
    long deadline = SystemClock.elapsedRealtime() + 12000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (check(predicate)) return;
      SystemClock.sleep(20);
    }
    throw new AssertionError("PiP state timed out");
  }

  private void toggle() {
    host.getTargetContext()
        .sendBroadcast(
            new Intent(host.getTargetContext().getPackageName() + ".PIP_PLAY_PAUSE")
                .setPackage(host.getTargetContext().getPackageName()));
  }

  void run() {
    Bundle result = new Bundle();
    try {
      String server = arguments.getString("server"), token = arguments.getString("token");
      if (server == null || new java.net.URI(server).getPort() != 18765)
        throw new IllegalArgumentException("Use isolated fixture port 18765");
      api = new ApiClient(server, token);
      Intent launch =
          new Intent(Intent.ACTION_MAIN)
              .setClassName(host.getTargetContext(), MainActivity.class.getName())
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
              .putExtra("server", server)
              .putExtra("token", token)
              .putExtra("autoStart", true);
      activity = host.startActivitySync(launch);
      Field field = MainActivity.class.getDeclaredField("playback");
      field.setAccessible(true);
      playback = (PlaybackController) field.get(activity);
      await(() -> playback.canResumeBuffered() && !playback.isPaused());
      long before = playback.positionMs();
      host.getUiAutomation().executeShellCommand("input keyevent 3").close();
      await(activity::isInPictureInPictureMode);
      long sequence = api.session().getLong("sequence");
      SystemClock.sleep(5000);
      if (check(playback::isPaused) || playback.positionMs() <= before + 1000)
        throw new AssertionError("Video paused on entering PiP");
      if (api.session().getLong("sequence") <= sequence)
        throw new AssertionError("PiP heartbeats stopped");
      result.putBoolean("homeEntersPipAndPlaybackContinues", true);
      Activity sharing =
          host.startActivitySync(
              new Intent()
                  .setClassName(host.getTargetContext(), SyncActivity.class.getName())
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      SystemClock.sleep(1000);
      if (!check(activity::isInPictureInPictureMode) || check(playback::isPaused))
        throw new AssertionError("Sharing screen closed the mini player");
      android.graphics.Bitmap screenshot = host.getUiAutomation().takeScreenshot();
      if (screenshot != null) {
        try (java.io.FileOutputStream output =
            new java.io.FileOutputStream(
                new java.io.File(
                    host.getTargetContext().getExternalFilesDir(null),
                    "syncro-mini-preview.png"))) {
          screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        screenshot.recycle();
      }
      result.putBoolean("miniContinuesOverSharingScreen", true);
      toggle();
      await(playback::isPaused);
      SystemClock.sleep(1200);
      if (!api.session().getString("owner").equals("pc"))
        throw new AssertionError("PiP pause was not checkpointed");
      toggle();
      await(() -> !playback.isPaused());
      SystemClock.sleep(1200);
      if (check(playback::isPaused))
        throw new AssertionError("Background resume was incorrectly paused");
      result.putBoolean("miniPlayerPauseAndResumeWork", true);
      host.runOnMainSync(activity::finish);
      SystemClock.sleep(1500);
      if (!api.session().getString("owner").equals("pc"))
        throw new AssertionError("Closing PiP lost the checkpoint");
      result.putBoolean("closingMiniPlayerCheckpointsPc", true);
      host.runOnMainSync(sharing::finish);
      result.putString("result", "passed");
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.toString());
      host.finish(Activity.RESULT_CANCELED, result);
    }
  }
}
