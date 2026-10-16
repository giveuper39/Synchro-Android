package dev.synchro.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.MediaPlayer;

/**
 * Runs against the isolated slow-PC fixture on port 18765. Never target a real viewing session.
 * Clicks use Android's main thread so control auto-hide cannot race a host-side UI dump.
 */
public final class PlaybackLatencyInstrumentation extends Instrumentation {
  private Bundle arguments;
  private Activity activity;
  private PlaybackController playback;
  private MediaPlayer nativePlayer;
  private String server, token;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    this.arguments = arguments;
    start();
  }

  private static Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private boolean onMain(java.util.function.BooleanSupplier predicate) {
    AtomicBoolean result = new AtomicBoolean();
    runOnMainSync(() -> result.set(predicate.getAsBoolean()));
    return result.get();
  }

  private void await(java.util.function.BooleanSupplier predicate) {
    long deadline = SystemClock.elapsedRealtime() + 12000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (onMain(predicate)) return;
      SystemClock.sleep(10);
    }
    throw new AssertionError("Playback state timed out");
  }

  private void click() {
    runOnMainSync(
        () -> {
          View button = activity.findViewById(R.id.play_pause);
          if (!button.isShown()) activity.findViewById(R.id.video_touch).performClick();
          button.performClick();
        });
  }

  private boolean resumeFinished() {
    try {
      return !(boolean) field(activity, "requestInFlight");
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private IMedia currentMedia() {
    AtomicReference<IMedia> result = new AtomicReference<>();
    runOnMainSync(() -> result.set(nativePlayer.getMedia()));
    return result.get();
  }

  private void assertSameMedia(IMedia expected) {
    IMedia actual = currentMedia();
    try {
      if (expected != actual)
        throw new AssertionError("Same-file resume replaced the native Media");
    } finally {
      if (actual != null) actual.release();
    }
  }

  private JSONObject request(String path, JSONObject body) throws Exception {
    java.net.HttpURLConnection connection =
        (java.net.HttpURLConnection) new java.net.URL(server + path).openConnection();
    try {
      connection.setConnectTimeout(3000);
      connection.setReadTimeout(5000);
      connection.setRequestProperty("Authorization", "Bearer " + token);
      if (body != null) {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream output = connection.getOutputStream()) {
          output.write(bytes);
        }
      }
      if (connection.getResponseCode() != 200) throw new AssertionError("Fixture HTTP failure");
      try (java.io.InputStream input = connection.getInputStream()) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return new JSONObject(bytes.toString("UTF-8"));
      }
    } finally {
      connection.disconnect();
    }
  }

  private void awaitPc() throws Exception {
    for (int attempt = 0; attempt < 60; attempt++) {
      if (request("/api/session", null).getString("owner").equals("pc")) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError("Pause checkpoint did not finish");
  }

  @Override
  public void onStart() {
    if ("coldStart".equals(arguments.getString("suite"))) {
      ColdStartChecks.run(this, arguments);
      return;
    }
    if ("pairing".equals(arguments.getString("suite"))) {
      PairingChecks.run(this, arguments);
      return;
    }
    if ("design".equals(arguments.getString("suite"))) {
      new DesignChecks(this, arguments).run();
      return;
    }
    if ("clipboardImages".equals(arguments.getString("suite"))) {
      new ClipboardImageChecks(this, arguments).run();
      return;
    }
    if ("updates".equals(arguments.getString("suite"))) {
      UpdateChecks.run(this);
      return;
    }
    if ("sharing".equals(arguments.getString("suite"))) {
      new SharingChecks(this, arguments).run();
      return;
    }
    if ("pip".equals(arguments.getString("suite"))) {
      new PipChecks(this, arguments).run();
      return;
    }
    Bundle result = new Bundle();
    try {
      server = arguments.getString("server");
      token = arguments.getString("token");
      if (server == null || new java.net.URI(server).getPort() != 18765 || token == null)
        throw new IllegalArgumentException("Use only the isolated fixture on port 18765");
      Intent intent = new Intent(Intent.ACTION_MAIN);
      intent.setClassName(getTargetContext(), MainActivity.class.getName());
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      intent.putExtra("server", server).putExtra("token", token).putExtra("autoStart", true);
      activity = startActivitySync(intent);
      playback = (PlaybackController) field(activity, "playback");
      nativePlayer = (MediaPlayer) field(playback, "player");
      await(() -> playback.canResumeBuffered() && nativePlayer.isPlaying());
      click();
      await(() -> playback.isPaused() && !nativePlayer.isPlaying());
      awaitPc();
      long started = SystemClock.elapsedRealtime();
      click();
      await(() -> !playback.isPaused() && nativePlayer.isPlaying());
      result.putLong("resumeMs", SystemClock.elapsedRealtime() - started);
      SystemClock.sleep(1000);

      click();
      await(() -> playback.isPaused() && !nativePlayer.isPlaying());
      awaitPc();
      IMedia buffered = currentMedia();
      try {
        // A settings edit makes resumeInPlace false, but does not change the file.
        request("/test/pc", new JSONObject().put("subtitle_delay_ms", 450).put("rate", 1.25));
        click();
        await(() -> resumeFinished() && !playback.isPaused() && nativePlayer.isPlaying());
        assertSameMedia(buffered);
        if (!onMain(() -> playback.subtitleDelayMs() == 450 && nativePlayer.getRate() == 1.25f))
          throw new AssertionError("Same-file PC settings were not applied");
        result.putBoolean("pcSettingsKeptNativeMedia", true);

        click();
        await(() -> playback.isPaused() && !nativePlayer.isPlaying());
        awaitPc();
        request("/test/pc", new JSONObject().put("position_ms", 45000).put("rate", 1));
        click();
        await(() -> resumeFinished() && Math.abs(nativePlayer.getTime() - 45000) < 2500);
        assertSameMedia(buffered);
        result.putBoolean("pcSeekKeptNativeMedia", true);
      } finally {
        if (buffered != null) buffered.release();
      }

      click();
      await(() -> playback.isPaused() && !nativePlayer.isPlaying());
      awaitPc();
      click();
      click(); // Pause before the fixture's delayed resume reply.
      SystemClock.sleep(1800);
      if (!onMain(() -> playback.isPaused() && !nativePlayer.isPlaying()))
        throw new AssertionError("A late resume reply restarted playback");
      awaitPc();
      result.putBoolean("quickPauseStayedPaused", true);

      request("/test/pc", new JSONObject().put("index", 1).put("position_ms", 120000));
      click();
      await(() -> playback.index() == 1 && playback.positionMs() >= 119000);
      await(nativePlayer::isPlaying);
      click();
      await(() -> playback.isPaused() && !nativePlayer.isPlaying());
      awaitPc();
      result.putBoolean("pcEpisodeAndPositionApplied", true);

      request("/test/pc", new JSONObject().put("index", 0).put("position_ms", 20000));
      click();
      click(); // New-file response must also respect the last Pause.
      SystemClock.sleep(2200);
      await(() -> playback.isPaused() && playback.index() == 0);
      if (onMain(nativePlayer::isPlaying)) throw new AssertionError("New file started after Pause");
      awaitPc();
      JSONObject paused = request("/api/session", null);
      if (paused.getInt("index") != 0 || Math.abs(paused.getLong("positionMs") - 20000) > 250)
        throw new AssertionError("The old phone position overwrote the new PC state");
      result.putBoolean("pcChangeWithQuickPauseStayedPaused", true);
      result.putString("result", "passed");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
