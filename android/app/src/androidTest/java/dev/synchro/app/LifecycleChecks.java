package dev.synchro.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

/** Real PiP expansion and a stopped task, isolated from all user playback. */
final class LifecycleChecks {
  private final Instrumentation host;
  private final Bundle arguments;
  private Activity activity;
  private PlaybackController playback;
  private MediaPlayer nativePlayer;
  private ApiClient api;

  LifecycleChecks(Instrumentation host, Bundle arguments) {
    this.host = host;
    this.arguments = arguments;
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
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
    throw new AssertionError("Lifecycle state timed out");
  }

  private boolean idle() {
    try {
      return !(boolean) field(activity, "requestInFlight");
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private void click() {
    host.runOnMainSync(
        () -> {
          View button = activity.findViewById(R.id.play_pause);
          if (!button.isShown()) activity.findViewById(R.id.video_touch).performClick();
          button.performClick();
        });
  }

  private void foreground() {
    host.getTargetContext()
        .startActivity(
            new Intent(Intent.ACTION_MAIN)
                .setClassName(host.getTargetContext(), MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    await(() -> !activity.isInPictureInPictureMode() && activity.hasWindowFocus());
  }

  private JSONObject awaitPc() throws Exception {
    for (int i = 0; i < 60; i++) {
      JSONObject state = api.session();
      if (state.getString("owner").equals("pc")) return state;
      SystemClock.sleep(50);
    }
    throw new AssertionError("Stop did not checkpoint the PC");
  }

  private void seek(long position) {
    host.runOnMainSync(() -> playback.seek(position));
    await(() -> Math.abs(nativePlayer.getTime() - position) < 1500);
    SystemClock.sleep(800);
  }

  void run() {
    Bundle result = new Bundle();
    IMedia media = null;
    try {
      String server = arguments.getString("server"), token = arguments.getString("token");
      if (server == null || new java.net.URI(server).getPort() != 18765 || token == null)
        throw new IllegalArgumentException("Use only isolated fixture port 18765");
      api = new ApiClient(server, token);
      activity =
          host.startActivitySync(
              new Intent(Intent.ACTION_MAIN)
                  .setClassName(host.getTargetContext(), MainActivity.class.getName())
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                  .putExtra("server", server)
                  .putExtra("token", token)
                  .putExtra("autoStart", true));
      playback = (PlaybackController) field(activity, "playback");
      nativePlayer = (MediaPlayer) field(playback, "player");
      await(() -> playback.canResumeBuffered() && nativePlayer.isPlaying());
      seek(105000);
      AtomicReference<IMedia> captured = new AtomicReference<>();
      host.runOnMainSync(() -> captured.set(nativePlayer.getMedia()));
      media = captured.get();

      long before = playback.positionMs();
      host.getUiAutomation().executeShellCommand("input keyevent 3").close();
      await(activity::isInPictureInPictureMode);
      SystemClock.sleep(2500);
      if (!check(() -> !playback.isPaused() && playback.positionMs() >= before - 1000))
        throw new AssertionError("Entering PiP rewound playback");
      foreground();
      long sequence = api.session().getLong("sequence");
      SystemClock.sleep(4500);
      JSONObject current = api.session();
      if (current.getLong("sequence") <= sequence)
        throw new AssertionError("PiP expansion stopped position heartbeats");
      if (Math.abs(current.getLong("positionMs") - playback.positionMs()) > 3000)
        throw new AssertionError("Expanded player published an old position");
      result.putBoolean("pipExpansionKeepsPositionAndHeartbeats", true);

      for (int i = 0; i < 3; i++) {
        click();
        await(() -> playback.isPaused() && !nativePlayer.isPlaying());
        awaitPc();
        click();
        await(() -> idle() && !playback.isPaused() && nativePlayer.isPlaying());
        SystemClock.sleep(2300);
        if (!check(() -> !playback.isPaused() && nativePlayer.isPlaying()))
          throw new AssertionError("Play was paused again after PiP expansion");
      }
      result.putBoolean("repeatedPlayRemainsPlayingAfterExpansion", true);

      seek(145000);
      long checkpoint = playback.positionMs();
      host.runOnMainSync(
          () -> {
            if (Build.VERSION.SDK_INT >= 31)
              activity.setPictureInPictureParams(
                  new PictureInPictureParams.Builder().setAutoEnterEnabled(false).build());
            activity.moveTaskToBack(true);
          });
      await(
          () ->
              !activity.isInPictureInPictureMode()
                  && playback.isPaused()
                  && !nativePlayer.isPlaying());
      JSONObject stopped = awaitPc();
      if (Math.abs(stopped.getLong("positionMs") - checkpoint) > 2000)
        throw new AssertionError("Minimizing saved the old PC position");
      foreground();
      click();
      await(() -> idle() && !playback.isPaused() && nativePlayer.isPlaying());
      SystemClock.sleep(2500);
      if (!check(() -> !playback.isPaused() && playback.positionMs() >= checkpoint - 1500))
        throw new AssertionError("Returning from a stopped task rewound or paused playback");
      final IMedia expectedMedia = media;
      if (!check(
          () -> {
            IMedia value = nativePlayer.getMedia();
            try {
              return value == expectedMedia;
            } finally {
              if (value != null) value.release();
            }
          })) throw new AssertionError("A window transition replaced the native Media");
      result.putBoolean("minimizeCheckpointsCurrentPosition", true);
      result.putBoolean("foregroundResumeKeepsMediaAndPosition", true);
      host.runOnMainSync(activity::finish);
      awaitPc();
      result.putString("result", "passed");
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.toString());
      host.finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (media != null) media.release();
    }
  }
}
