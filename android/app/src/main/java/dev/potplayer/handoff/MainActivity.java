package dev.potplayer.handoff;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates connection and handoff; presentation and native playback are separate. */
public final class MainActivity extends AppCompatActivity implements PlaybackController.Listener {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private PlaybackController playback;
  private ReceiverUi ui;
  private PlayerSheets sheets;
  private PipController pip;
  private ApiClient activeApi;
  private EditText serverInput, tokenInput;
  private Button connect, takeover, retry;
  private String deviceName = "Ваш компьютер";
  private long sequence, lastHeartbeatAt;
  private boolean requestInFlight, heartbeatPending, visible, destroyed, resuming;
  private ProgressUpdate failedProgress;
  private final ActivityResultLauncher<ScanOptions> scanner =
      registerForActivityResult(
          new ScanContract(),
          result -> {
            if (result.getContents() != null) receiveQr(result.getContents());
          });

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
    ui =
        new ReceiverUi(
            this,
            this::action,
            fraction -> {
              if (playback != null && playback.hasSession())
                playback.seek((long) (playback.durationMs() * fraction));
            });
    serverInput = ui.serverInput;
    tokenInput = ui.tokenInput;
    connect = ui.connect;
    takeover = ui.takeover;
    retry = ui.retry;
    playback = new PlaybackController(this, this);
    sheets = new PlayerSheets(this, playback, ui::showControls);
    pip = new PipController(this, () -> action(ReceiverUi.Action.PLAY_PAUSE));
    SharedPreferences preferences = ConnectionSettings.preferences(this);
    serverInput.setText(preferences.getString("server", ""));
    tokenInput.setText(preferences.getString("token", ""));
    deviceName = preferences.getString("computerName", "Ваш компьютер");
    ui.knownComputer(deviceName, serverInput.getText().toString(), false);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (ui.isInPlayer()) leavePlayer();
                else finish();
              }
            });
    applyIntent(getIntent());
    main.post(tick);
  }

  @Override
  protected void onStart() {
    super.onStart();
    visible = true;
    playback.attach(ui.video);
  }

  @Override
  protected void onPause() {
    visible = false;
    super.onPause();
  }

  @Override
  protected void onStop() {
    // PiP remains visible and reaches onPause, not onStop. Closing the mini-player
    // or truly hiding the activity checkpoints playback and stops local audio.
    if (playback.hasSession() && !playback.isPaused()) playback.pauseForHandoff();
    playback.detach();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    main.removeCallbacksAndMessages(null);
    sheets.close();
    pip.close();
    ui.release();
    playback.release();
    network.shutdown();
    super.onDestroy();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    sheets.close();
    setIntent(intent);
    applyIntent(intent);
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    ui.onConfigurationChanged();
  }

  @Override
  protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    pip.leaving();
  }

  @Override
  public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
    super.onPictureInPictureModeChanged(inPip, config);
    ui.pictureInPicture(inPip);
  }

  private void action(ReceiverUi.Action action) {
    // Pause/Home remain responsive during the background ownership check.
    // Other edits wait for its new session ID so old events cannot target a new file.
    if (resuming && action != ReceiverUi.Action.PLAY_PAUSE && action != ReceiverUi.Action.HOME)
      return;
    switch (action) {
      case SCAN:
        ScanOptions options =
            new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(QrScannerActivity.class);
        scanner.launch(options);
        break;
      case CONNECT:
        connect(false);
        break;
      case TAKEOVER:
        connect(true);
        break;
      case HOME:
        leavePlayer();
        break;
      case PLAY_PAUSE:
        if (playback.isPaused()) {
          if (resuming) playback.resume();
          else resumePlayback();
        } else playback.pauseForHandoff();
        break;
      case PREVIOUS:
        playback.selectItem(playback.index() - 1);
        break;
      case NEXT:
        playback.selectItem(playback.index() + 1);
        break;
      case REPLAY:
        playback.seek(playback.positionMs() - 10000);
        break;
      case FORWARD:
        playback.seek(playback.positionMs() + 10000);
        break;
      case AUDIO:
        sheets.tracks(false);
        break;
      case SUBTITLES:
        sheets.tracks(true);
        break;
      case QUEUE:
        sheets.queue();
        break;
      case SETTINGS:
        sheets.settings();
        break;
      case RATE:
        sheets.rate();
        break;
      case RETRY:
        retryProgress();
        break;
      case ABOUT:
        sheets.about();
        break;
      case UPDATES:
        startActivity(new Intent(this, UpdateActivity.class));
        break;
      case CLIPBOARD:
      case TABS:
        startActivity(
            new Intent(this, SyncActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("section", action == ReceiverUi.Action.TABS ? "tabs" : "clipboard"));
        break;
      case MINI_PLAYER:
        if (!pip.enter()) onStatus("Мини-плеер недоступен в настройках Android");
        break;
    }
  }

  private void leavePlayer() {
    if (playback.hasSession() && !playback.isPaused()) playback.pauseForHandoff();
    ui.showConnection();
    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
  }

  private void receiveQr(String contents) {
    try {
      PairingCode code = PairingCode.parse(contents);
      if (playback.hasSession() && !playback.isPaused()) playback.pauseForHandoff();
      ui.showConnection();
      serverInput.setText(code.server);
      tokenInput.setText(code.token);
      deviceName = code.name;
      ui.knownComputer(deviceName, code.server, false);
      connect(false);
    } catch (IllegalArgumentException error) {
      onStatus(error.getMessage());
    }
  }

  private void applyIntent(Intent intent) {
    if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
      receiveQr(intent.getData().toString());
      return;
    }
    // Never send a previously saved secret to a server supplied by another app.
    if (intent.hasExtra("server") && !intent.hasExtra("token")) tokenInput.setText("");
    if (intent.hasExtra("server")) serverInput.setText(intent.getStringExtra("server"));
    if (intent.hasExtra("token")) tokenInput.setText(intent.getStringExtra("token"));
    ui.knownComputer(deviceName, serverInput.getText().toString(), false);
    if (intent.hasExtra("server")
        && intent.hasExtra("token")
        && intent.getBooleanExtra("autoStart", false)) main.post(() -> connect(true));
  }

  private void connect(boolean takePlayback) {
    if (requestInFlight) return;
    final ApiClient candidate;
    try {
      candidate = new ApiClient(serverInput.getText().toString(), tokenInput.getText().toString());
    } catch (IllegalArgumentException e) {
      onStatus(e.getMessage());
      return;
    }
    requestInFlight = true;
    ui.busy(true);
    onStatus(takePlayback ? "Получаем видео и плейлист с компьютера…" : "Проверяем подключение…");
    network.execute(
        () -> {
          try {
            if (takePlayback) {
              // Unlike progress, takeover is not blindly retried: it creates a new session.
              SessionState next = candidate.takeover();
              main.post(
                  () -> {
                    if (destroyed) return;
                    activeApi = candidate;
                    sequence = next.sequence;
                    failedProgress = null;
                    retry.setVisibility(View.GONE);
                    saveConnection();
                    ui.showPlayer();
                    playback.accept(
                        next); // Keep the old player state until a complete response arrives.
                    finishConnectionRequest();
                    if (!visible && !isInPictureInPictureMode()) playback.pauseForHandoff();
                  });
            } else {
              candidate.session();
              main.post(
                  () -> {
                    if (destroyed) return;
                    saveConnection();
                    ui.knownComputer(deviceName, candidate.server(), true);
                    onStatus("Компьютер подключён. Можно продолжать просмотр.");
                    finishConnectionRequest();
                  });
            }
          } catch (Exception e) {
            main.post(
                () -> {
                  if (destroyed) return;
                  onStatus("Подключение не удалось: " + readableError(e));
                  finishConnectionRequest();
                });
          }
        });
  }

  private void resumePlayback() {
    if (requestInFlight) return;
    if (activeApi == null || !playback.canResumeBuffered()) {
      connect(true);
      return;
    }
    ApiClient api = activeApi;
    String previousSession = playback.session().sessionId;
    resuming = true;
    requestInFlight = true;
    failedProgress = null;
    retry.setVisibility(View.GONE);
    ui.busy(true, false);
    // The PC is already paused by the handoff. Reuse decoded/buffered media now,
    // then reconcile any PC edits when its ordered ownership check completes.
    playback.resume();
    lastHeartbeatAt = android.os.SystemClock.elapsedRealtime();
    network.execute(
        () -> {
          try {
            SessionState next;
            try {
              next = api.resume(previousSession);
            } catch (ApiClient.ApiException error) {
              if (error.status != 404) throw error;
              next = api.takeover(); // Compatibility with a server awaiting its update.
            }
            SessionState confirmed = next;
            main.post(
                () -> {
                  if (destroyed) return;
                  activeApi = api;
                  sequence = confirmed.sequence;
                  playback.acceptResume(confirmed);
                  resuming = false;
                  finishConnectionRequest();
                  if (!visible && !isInPictureInPictureMode() && !playback.isPaused())
                    playback.pauseLocally();
                  // A tap on Pause during the check belongs to the confirmed new
                  // session, never to the obsolete ID captured before the request.
                  sendProgress(playback.isPaused(), false);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (destroyed) return;
                  resuming = false;
                  playback.pauseLocally();
                  finishConnectionRequest();
                  onStatus("Подключение не удалось: " + readableError(error));
                });
          }
        });
  }

  private void saveConnection() {
    ConnectionSettings.preferences(this)
        .edit()
        .putString("server", serverInput.getText().toString().trim())
        .putString("token", tokenInput.getText().toString().trim())
        .putString("computerName", deviceName)
        .apply();
    ui.knownComputer(deviceName, serverInput.getText().toString().trim(), true);
  }

  private void finishConnectionRequest() {
    requestInFlight = false;
    ui.busy(false);
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (destroyed) return;
          if (playback != null && playback.hasSession()) {
            playback.refresh();
            updateControls();
            long now = android.os.SystemClock.elapsedRealtime();
            if ((visible || isInPictureInPictureMode())
                && !playback.isPaused()
                && now - lastHeartbeatAt >= 2000) {
              lastHeartbeatAt = now;
              sendProgress(false, true);
            }
          }
          main.postDelayed(this, 500);
        }
      };

  @Override
  public void onProgressRequested(boolean reliable) {
    sendProgress(reliable, false);
  }

  private void sendProgress(boolean reliable, boolean heartbeat) {
    if (resuming
        || activeApi == null
        || !playback.hasSession()
        || (heartbeat && (heartbeatPending || requestInFlight))) return;
    ProgressUpdate update = playback.snapshot(++sequence);
    ApiClient api = activeApi;
    failedProgress = null; // A newer state supersedes a previous failed pause, e.g. after resume.
    retry.setVisibility(View.GONE);
    if (heartbeat) heartbeatPending = true;
    network.execute(
        () -> {
          Exception failure = null;
          int attempts = reliable ? 3 : 1;
          for (int attempt = 0; attempt < attempts; attempt++) {
            try {
              api.progress(update);
              failure = null;
              break;
            } catch (Exception e) {
              failure = e;
              if (e instanceof ApiClient.ApiException && !((ApiClient.ApiException) e).retryable())
                break;
              // Retries stay on this executor, preserving sequence order. Same sequence is
              // idempotent.
            }
          }
          Exception result = failure;
          main.post(
              () -> {
                if (heartbeat) heartbeatPending = false;
                if (destroyed
                    || resuming
                    || !playback.hasSession()
                    || !playback.session().sessionId.equals(update.sessionId)) return;
                if (result != null) {
                  if (result instanceof ApiClient.ApiException
                      && ((ApiClient.ApiException) result).status == 409) {
                    playback.pauseLocally();
                    activeApi = null;
                    onStatus("Просмотр на компьютере. Нажмите ▶, чтобы продолжить на телефоне.");
                    return;
                  }
                  onStatus("Позиция не передана: " + readableError(result));
                  if (reliable && sequence == update.sequence) {
                    failedProgress = update;
                    retry.setVisibility(View.VISIBLE);
                  }
                } else if (reliable && sequence == update.sequence) {
                  onStatus("Позиция и настройки переданы на компьютер");
                }
              });
        });
  }

  private void retryProgress() {
    if (failedProgress == null || activeApi == null) return;
    // Capture the current state with a new monotonically increasing sequence.
    sendProgress(true, false);
  }

  @Override
  public void onChanged() {
    updateControls();
  }

  @Override
  public void onStatus(String message) {
    ui.status(message);
  }

  private void updateControls() {
    if (playback != null) {
      ui.render(playback);
      if (pip != null) pip.update(ui.isInPlayer() && playback.hasSession(), playback.isPaused());
    }
  }

  private static String readableError(Exception error) {
    if (error instanceof java.net.SocketTimeoutException) return "сервер не ответил вовремя";
    if (error instanceof java.net.ConnectException) return "сервер недоступен; проверьте сеть";
    if (error instanceof ApiClient.ApiException) {
      int code = ((ApiClient.ApiException) error).status;
      if (code == 401) return "ключ устарел; отсканируйте QR-код заново";
      if (code == 503) return "PotPlayer недоступен; закройте его открытые диалоги";
      return "компьютер не смог обработать запрос";
    }
    if (error instanceof IOException) return "ошибка сети";
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }
}
