package dev.synchro.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.videolan.libvlc.MediaPlayer;

/** Cold start and a non-keyframe position, using only the isolated synthetic fixture. */
final class ColdStartChecks {
  static void run(Instrumentation host, Bundle args) {
    Bundle result = new Bundle();
    Activity activity = null;
    try {
      String server = args.getString("server"), token = args.getString("token");
      if (new java.net.URI(server).getPort() != 18765)
        throw new IllegalArgumentException("Fixture only");
      ApiClient api = new ApiClient(server, token);
      api.request(
          "POST",
          "/test/pc",
          new JSONObject()
              .put("index", 0)
              .put("position_ms", 72567)
              .put("paused", true)
              .toString());
      long started = SystemClock.elapsedRealtime();
      activity =
          host.startActivitySync(
              new Intent()
                  .setClassName(host.getTargetContext(), MainActivity.class.getName())
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                  .putExtra("server", server)
                  .putExtra("token", token)
                  .putExtra("autoStart", true));
      Field p = MainActivity.class.getDeclaredField("playback");
      p.setAccessible(true);
      PlaybackController playback = (PlaybackController) p.get(activity);
      Field n = PlaybackController.class.getDeclaredField("player");
      n.setAccessible(true);
      MediaPlayer nativePlayer = (MediaPlayer) n.get(playback);
      long end = SystemClock.elapsedRealtime() + 12000;
      boolean ready = false;
      while (SystemClock.elapsedRealtime() < end) {
        AtomicBoolean state = new AtomicBoolean();
        host.runOnMainSync(
            () ->
                state.set(
                    playback.canResumeBuffered()
                        && nativePlayer.isPlaying()
                        && nativePlayer.getTime() >= 72000));
        if (state.get()) {
          ready = true;
          break;
        }
        SystemClock.sleep(10);
      }
      if (!ready) throw new AssertionError("Cold startup timed out");
      result.putLong("coldStartMs", SystemClock.elapsedRealtime() - started);
      host.runOnMainSync(playback::pauseLocally);
      long position = nativePlayer.getTime();
      if (Math.abs(position - 72567) > 750)
        throw new AssertionError("Initial non-keyframe position drifted");
      result.putLong("initialSeekErrorMs", Math.abs(position - 72567));
      result.putString("result", "passed");
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.toString());
      host.finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (activity != null) {
        Activity current = activity;
        host.runOnMainSync(current::finish);
      }
    }
  }
}
