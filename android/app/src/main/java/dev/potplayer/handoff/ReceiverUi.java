package dev.potplayer.handoff;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.slider.Slider;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Connection and immersive player presentation. No HTTP or playback ownership logic. */
final class ReceiverUi {
  enum Action {
    SCAN,
    CONNECT,
    TAKEOVER,
    HOME,
    PLAY_PAUSE,
    PREVIOUS,
    NEXT,
    REPLAY,
    FORWARD,
    AUDIO,
    SUBTITLES,
    QUEUE,
    SETTINGS,
    RATE,
    RETRY,
    ABOUT,
    CLIPBOARD,
    TABS,
    UPDATES,
    MINI_PLAYER
  }

  final EditText serverInput, tokenInput;
  final Button connect, takeover, retry;
  final VLCVideoLayout video;
  private final AppCompatActivity activity;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final View connectionScreen, playerScreen, controls, manualFields, savedComputer;
  private final TextView homeStatus,
      playerStatus,
      title,
      episode,
      time,
      computerName,
      computerAddress,
      badge;
  private final ImageButton playPause, previous, next, fullscreen;
  private final Button rate, scan;
  private final Slider seek;
  private final WindowInsetsControllerCompat systemBars;
  private boolean inPlayer, seeking, paused = true;
  private boolean connecting, loading;
  private boolean inPip;
  private String transientStatus = "";
  private long statusUntil;
  private long duration;
  private final Runnable hideControls = () -> setControlsVisible(false);

  ReceiverUi(AppCompatActivity activity, Consumer<Action> actions, DoubleConsumer seekAction) {
    this.activity = activity;
    activity.setContentView(R.layout.activity_main);
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    systemBars =
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
    systemBars.setSystemBarsBehavior(
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    connectionScreen = activity.findViewById(R.id.connection_screen);
    playerScreen = activity.findViewById(R.id.player_screen);
    controls = activity.findViewById(R.id.player_controls);
    manualFields = activity.findViewById(R.id.manual_fields);
    savedComputer = activity.findViewById(R.id.saved_computer);
    serverInput = activity.findViewById(R.id.server_input);
    tokenInput = activity.findViewById(R.id.token_input);
    connect = activity.findViewById(R.id.connect);
    takeover = activity.findViewById(R.id.takeover);
    retry = activity.findViewById(R.id.retry);
    video = activity.findViewById(R.id.video);
    homeStatus = activity.findViewById(R.id.home_status);
    playerStatus = activity.findViewById(R.id.player_status);
    title = activity.findViewById(R.id.video_title);
    episode = activity.findViewById(R.id.video_episode);
    time = activity.findViewById(R.id.time);
    computerName = activity.findViewById(R.id.computer_name);
    computerAddress = activity.findViewById(R.id.computer_address);
    badge = activity.findViewById(R.id.connection_badge);
    playPause = activity.findViewById(R.id.play_pause);
    previous = activity.findViewById(R.id.previous);
    next = activity.findViewById(R.id.next);
    fullscreen = activity.findViewById(R.id.fullscreen);
    rate = activity.findViewById(R.id.rate);
    scan = activity.findViewById(R.id.scan_qr);
    seek = activity.findViewById(R.id.seek);
    LinearLayout home = (LinearLayout) savedComputer.getParent();
    home.removeView(savedComputer);
    home.addView(savedComputer, home.indexOfChild(scan));
    View features = activity.findViewById(R.id.home_features);
    home.removeView(features);
    home.addView(features, home.indexOfChild(scan));
    com.google.android.material.button.MaterialButton updates =
        new com.google.android.material.button.MaterialButton(activity);
    updates.setText("Обновления Синхро");
    home.addView(updates);
    updates.setOnClickListener(view -> actions.accept(Action.UPDATES));
    int[] ids = {
      R.id.scan_qr,
      R.id.connect,
      R.id.takeover,
      R.id.player_back,
      R.id.play_pause,
      R.id.previous,
      R.id.next,
      R.id.replay,
      R.id.forward,
      R.id.audio,
      R.id.subtitles,
      R.id.player_queue,
      R.id.player_more,
      R.id.rate,
      R.id.retry,
      R.id.home_more,
      R.id.hub_clipboard,
      R.id.hub_tabs,
      R.id.mini_player
    };
    Action[] events = {
      Action.SCAN,
      Action.CONNECT,
      Action.TAKEOVER,
      Action.HOME,
      Action.PLAY_PAUSE,
      Action.PREVIOUS,
      Action.NEXT,
      Action.REPLAY,
      Action.FORWARD,
      Action.AUDIO,
      Action.SUBTITLES,
      Action.QUEUE,
      Action.SETTINGS,
      Action.RATE,
      Action.RETRY,
      Action.ABOUT,
      Action.CLIPBOARD,
      Action.TABS,
      Action.MINI_PLAYER
    };
    for (int i = 0; i < ids.length; i++) {
      Action event = events[i];
      activity
          .findViewById(ids[i])
          .setOnClickListener(
              view -> {
                showControls();
                actions.accept(event);
              });
    }
    activity
        .findViewById(R.id.manual_toggle)
        .setOnClickListener(
            view -> {
              manualFields.setVisibility(
                  manualFields.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
              if (manualFields.getVisibility() == View.VISIBLE) {
                manualFields.post(
                    () ->
                        manualFields.requestRectangleOnScreen(
                            new android.graphics.Rect(
                                0, 0, manualFields.getWidth(), manualFields.getHeight()),
                            false));
              }
            });
    fullscreen.setOnClickListener(
        view -> {
          boolean landscape =
              activity.getResources().getConfiguration().orientation
                  == Configuration.ORIENTATION_LANDSCAPE;
          activity.setRequestedOrientation(
              landscape
                  ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                  : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
          showControls();
        });
    seek.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(Slider slider) {
            seeking = true;
            handler.removeCallbacks(hideControls);
          }

          @Override
          public void onStopTrackingTouch(Slider slider) {
            seeking = false;
            seekAction.accept(slider.getValue() / 10000.0);
            showControls();
          }
        });
    seek.addOnChangeListener(
        (slider, value, fromUser) -> {
          if (fromUser)
            time.setText(
                formatTime((long) (duration * value / 10000)) + " / " + formatTime(duration));
        });
    View touch = activity.findViewById(R.id.video_touch);
    touch.setOnClickListener(
        view -> {
          if (controls.getVisibility() == View.VISIBLE) setControlsVisible(false);
          else showControls();
        });
    GestureDetector gestures =
        new GestureDetector(
            activity,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent event) {
                return true;
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent event) {
                touch.performClick();
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent event) {
                boolean backward = event.getX() < touch.getWidth() / 2f;
                actions.accept(backward ? Action.REPLAY : Action.FORWARD);
                showSeekFeedback(backward);
                return true;
              }
            });
    touch.setOnTouchListener((view, event) -> gestures.onTouchEvent(event));
    ViewCompat.setOnApplyWindowInsetsListener(
        activity.findViewById(R.id.app_root),
        (view, insets) -> {
          androidx.core.graphics.Insets safe =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          connectionScreen.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          controls.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return insets;
        });
    showConnection();
    onConfigurationChanged();
  }

  boolean isInPlayer() {
    return inPlayer;
  }

  void showConnection() {
    inPlayer = false;
    handler.removeCallbacks(hideControls);
    playerScreen.setVisibility(View.GONE);
    connectionScreen.setVisibility(View.VISIBLE);
    systemBars.show(WindowInsetsCompat.Type.systemBars());
    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  void showPlayer() {
    boolean entering = !inPlayer;
    inPlayer = true;
    if (entering)
      activity.setRequestedOrientation(
          android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    connectionScreen.setVisibility(View.GONE);
    playerScreen.setVisibility(View.VISIBLE);
    systemBars.hide(WindowInsetsCompat.Type.systemBars());
    ViewCompat.requestApplyInsets(playerScreen);
    showControls();
  }

  void onConfigurationChanged() {
    if (inPlayer) systemBars.hide(WindowInsetsCompat.Type.systemBars());
    boolean landscape =
        activity.getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_LANDSCAPE;
    playerStatus.setVisibility(landscape ? View.GONE : View.VISIBLE);
    fullscreen.setImageResource(landscape ? R.drawable.ic_portrait : R.drawable.ic_fullscreen);
    fullscreen.setContentDescription(landscape ? "Портретный режим" : "Альбомный режим");
  }

  void knownComputer(String name, String address, boolean connected) {
    activity
        .findViewById(R.id.home_features)
        .setVisibility(address.trim().isEmpty() ? View.GONE : View.VISIBLE);
    activity
        .findViewById(R.id.connection_intro)
        .setVisibility(address.trim().isEmpty() ? View.VISIBLE : View.GONE);
    if (address.trim().isEmpty()) {
      savedComputer.setVisibility(View.GONE);
      return;
    }
    savedComputer.setVisibility(View.VISIBLE);
    if (!connected)
      homeStatus.setText(
          "Продолжите просмотр с сохранённого компьютера или отсканируйте новый QR-код.");
    scan.setText("Подключить другой ПК");
    com.google.android.material.button.MaterialButton scanButton =
        (com.google.android.material.button.MaterialButton) scan;
    scanButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1C1E21));
    scanButton.setTextColor(activity.getColor(R.color.amber));
    scanButton.setIconTint(
        android.content.res.ColorStateList.valueOf(activity.getColor(R.color.amber)));
    scanButton.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF424448));
    scanButton.setStrokeWidth(dp(1));
    computerName.setText(name.isEmpty() ? "Ваш компьютер" : name);
    computerAddress.setText(address.replaceFirst("^https?://", ""));
    badge.setText(connected ? "В СЕТИ" : "СОХРАНЁН");
    badge.setTextColor(activity.getColor(connected ? R.color.green : R.color.text_secondary));
  }

  void status(String message) {
    homeStatus.setText(message);
    playerStatus.setText(message);
    if (inPlayer
        && (message.startsWith("Позиция")
            || message.startsWith("Просмотр на компьютере")
            || message.startsWith("Подключение не удалось"))) {
      transientStatus = message;
      statusUntil = android.os.SystemClock.elapsedRealtime() + 5000;
      showControls();
    }
  }

  void busy(boolean busy) {
    busy(busy, true);
  }

  void busy(boolean busy, boolean blockPlayback) {
    connecting = busy && blockPlayback;
    scan.setEnabled(!busy);
    connect.setEnabled(!busy);
    takeover.setEnabled(!busy);
  }

  void render(PlaybackController playback) {
    if (!playback.hasSession()) return;
    SessionState session = playback.session();
    title.setText(session.items.get(playback.index()).title);
    if (android.os.SystemClock.elapsedRealtime() < statusUntil) {
      episode.setText(transientStatus);
      episode.setTextColor(
          transientStatus.startsWith("Позиция и настройки переданы") ? 0xFF8BD5AF : 0xFFFFD34E);
    } else {
      episode.setText(
          "Серия " + (playback.index() + 1) + " из " + session.items.size() + "  ·  С компьютера");
      episode.setTextColor(0xB3FFFFFF);
    }
    boolean nowLoading = connecting || playback.isPreparing();
    activity.findViewById(R.id.loading).setVisibility(nowLoading ? View.VISIBLE : View.GONE);
    activity
        .findViewById(R.id.transport_controls)
        .setVisibility(nowLoading ? View.GONE : View.VISIBLE);
    if (nowLoading != loading) {
      loading = nowLoading;
      if (!loading) showControls();
    }
    boolean nowPaused = playback.isPaused();
    playPause.setImageResource(nowPaused ? R.drawable.ic_play : R.drawable.ic_pause);
    playPause.setContentDescription(
        nowPaused ? "Продолжить на телефоне" : "Пауза и передача позиции на компьютер");
    if (nowPaused != paused) {
      paused = nowPaused;
      showControls();
    }
    if (inPlayer && !paused)
      activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    previous.setEnabled(playback.index() > 0);
    previous.setAlpha(previous.isEnabled() ? 1f : .35f);
    next.setEnabled(playback.index() + 1 < session.items.size());
    next.setAlpha(next.isEnabled() ? 1f : .35f);
    duration = playback.durationMs();
    if (!seeking) {
      seek.setValue(duration > 0 ? Math.min(10000f, playback.positionMs() * 10000f / duration) : 0);
      time.setText(formatTime(playback.positionMs()) + " / " + formatTime(duration));
    }
    rate.setText(
        String.format(Locale.ROOT, "%s×", Float.toString(playback.rate()).replaceAll("\\.0$", "")));
  }

  void showControls() {
    handler.removeCallbacks(hideControls);
    if (!inPlayer || inPip) return;
    systemBars.hide(WindowInsetsCompat.Type.systemBars());
    controls.animate().cancel();
    controls.setAlpha(1f);
    controls.setVisibility(View.VISIBLE);
    if (!paused
        && !activity
            .getSystemService(android.view.accessibility.AccessibilityManager.class)
            .isTouchExplorationEnabled()) {
      handler.postDelayed(hideControls, 3500);
    }
  }

  void pictureInPicture(boolean enabled) {
    inPip = enabled;
    handler.removeCallbacks(hideControls);
    controls.animate().cancel();
    controls.setVisibility(enabled ? View.GONE : View.VISIBLE);
    activity.findViewById(R.id.video_touch).setVisibility(enabled ? View.GONE : View.VISIBLE);
    activity.findViewById(R.id.loading).setVisibility(View.GONE);
    if (!enabled) showControls();
  }

  private void setControlsVisible(boolean visible) {
    handler.removeCallbacks(hideControls);
    if (visible) showControls();
    else
      controls
          .animate()
          .alpha(0f)
          .setDuration(180)
          .withEndAction(() -> controls.setVisibility(View.GONE))
          .start();
  }

  private void showSeekFeedback(boolean backward) {
    TextView feedback = new TextView(activity);
    feedback.setText(backward ? "−10 с" : "+10 с");
    feedback.setTextSize(22);
    feedback.setTextColor(Color.WHITE);
    feedback.setGravity(Gravity.CENTER);
    int p = dp(20);
    feedback.setPadding(p, p, p, p);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0x99000000);
    background.setCornerRadius(dp(40));
    feedback.setBackground(background);
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(
            -2, -2, Gravity.CENTER_VERTICAL | (backward ? Gravity.START : Gravity.END));
    params.setMargins(dp(40), 0, dp(40), 0);
    ((FrameLayout) playerScreen).addView(feedback, params);
    feedback
        .animate()
        .alpha(0f)
        .setStartDelay(350)
        .setDuration(200)
        .withEndAction(() -> ((FrameLayout) playerScreen).removeView(feedback))
        .start();
  }

  void release() {
    handler.removeCallbacksAndMessages(null);
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  static String formatTime(long milliseconds) {
    long seconds = Math.max(0, milliseconds) / 1000;
    return seconds >= 3600
        ? String.format(
            Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }
}
